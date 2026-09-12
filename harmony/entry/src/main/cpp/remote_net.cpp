/* remote_net.cpp — SMB / SFTP protocol clients for online book reading.
 *
 * SMB  : libsmb2 (internal crypto, SMB2/3, sync API driven by its own poll loop)
 * SFTP : libssh2 (blocking mode, OpenSSL statically linked inside libssh2.so.1)
 *
 * All exported functions are async (napi_async_work) so the JS thread is never
 * blocked; heavy work happens on the libuv worker pool. Each connection carries
 * a pthread mutex: one operation at a time per connection, mirroring the
 * Android SmbDataSource/SftpDataSource readLock. Read failures retry once
 * through a fresh connection (mirror of Android reopenQuiet()).
 *
 * Error codes (rejected promise string "<CODE>: <message>"):
 *   SMB_AUTH / SMB_ERROR       - SMB auth vs network/other failure
 *   SFTP_AUTH / SFTP_ERROR     - SFTP auth vs network/other failure
 *   SFTP_HOSTKEY_CHANGED       - stored host key fingerprint mismatch (TOFU)
 *
 * Registration: RegisterRemoteNet(env, exports) is called from mupdf_napi.cpp Init().
 */
#include <napi/native_api.h>

#include <pthread.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <map>
#include <string>
#include <vector>

#include "smb2/smb2.h"
#include "smb2/libsmb2.h"
#include "libssh2.h"
#include "libssh2_sftp.h"

namespace {

/* ------------------------------------------------------------------ */
/* generic async job plumbing                                          */
/* ------------------------------------------------------------------ */

struct NetError {
    std::string code;
    std::string msg;
    NetError(std::string c, std::string m) : code(std::move(c)), msg(std::move(m)) {}
};

struct NetJob {
    napi_deferred deferred = nullptr;
    std::function<void()> run;
    std::string result;        /* JSON/string payload when !isBin */
    std::vector<uint8_t> bin;  /* ArrayBuffer payload when isBin */
    bool isBin = false;
    bool ok = false;
    std::string errCode;
    std::string errMsg;
};

static void NetJobExecute(napi_env /*env*/, void *data)
{
    auto *job = static_cast<NetJob *>(data);
    try {
        job->run();
        job->ok = true;
    } catch (const NetError &e) {
        job->errCode = e.code;
        job->errMsg = e.msg;
    } catch (const std::exception &e) {
        job->errCode = "NET_ERROR";
        job->errMsg = e.what();
    } catch (...) {
        job->errCode = "NET_ERROR";
        job->errMsg = "unknown failure";
    }
}

static void NetJobComplete(napi_env env, napi_status status, void *data)
{
    auto *job = static_cast<NetJob *>(data);
    napi_value value = nullptr;
    bool resolved = false;
    if (status == napi_ok && job->ok) {
        if (job->isBin) {
            void *buf = nullptr;
            if (napi_create_arraybuffer(env, job->bin.size(), &buf, &value) == napi_ok) {
                if (!job->bin.empty()) {
                    memcpy(buf, job->bin.data(), job->bin.size());
                }
                resolved = true;
            }
        } else {
            if (napi_create_string_utf8(env, job->result.c_str(), job->result.size(), &value) == napi_ok) {
                resolved = true;
            }
        }
    }
    if (resolved) {
        napi_resolve_deferred(env, job->deferred, value);
    } else {
        std::string msg = job->errCode.empty() ? std::string("NET_ERROR") : job->errCode;
        msg += ": ";
        msg += job->errMsg.empty() ? std::string("job failed") : job->errMsg;
        napi_value ev = nullptr;
        if (napi_create_string_utf8(env, msg.c_str(), msg.size(), &ev) != napi_ok) {
            ev = nullptr;
        }
        napi_reject_deferred(env, job->deferred, ev);
    }
    delete job;
}

/* Creates the promise + async work and queues it. Returns the promise. */
static napi_value StartNetJob(napi_env env, NetJob *job, const char *name)
{
    napi_value deferredVal = nullptr;
    if (napi_create_promise(env, &job->deferred, &deferredVal) != napi_ok || job->deferred == nullptr) {
        delete job;
        napi_throw_error(env, "PROMISE_FAILED", "cannot create promise");
        return nullptr;
    }
    napi_async_work work = nullptr;
    napi_value nameVal = nullptr;
    napi_create_string_utf8(env, name, NAPI_AUTO_LENGTH, &nameVal);
    if (napi_create_async_work(env, nullptr, nameVal, NetJobExecute, NetJobComplete, job, &work) != napi_ok ||
        work == nullptr) {
        delete job;
        napi_throw_error(env, "WORK_FAILED", "cannot create async work");
        return nullptr;
    }
    if (napi_queue_async_work(env, work) != napi_ok) {
        napi_delete_async_work(env, work);
        delete job;
        napi_throw_error(env, "QUEUE_FAILED", "cannot queue async work");
        return nullptr;
    }
    return deferredVal;
}

/* ------------------------- arg helpers ---------------------------- */

static std::string GetStrArg(napi_env env, napi_value v)
{
    napi_valuetype t = napi_undefined;
    napi_typeof(env, v, &t);
    if (t != napi_string) {
        return std::string();
    }
    size_t len = 0;
    napi_get_value_string_utf8(env, v, nullptr, 0, &len);
    std::string out(len, '\0');
    if (len > 0) {
        napi_get_value_string_utf8(env, v, &out[0], len + 1, &len);
        out.resize(len);
    }
    return out;
}

static int64_t GetIntArg(napi_env env, napi_value v)
{
    double d = 0;
    if (napi_get_value_double(env, v, &d) != napi_ok) {
        return 0;
    }
    return static_cast<int64_t>(d);
}

static bool GetBoolArg(napi_env env, napi_value v)
{
    bool b = false;
    napi_get_value_bool(env, v, &b);
    return b;
}

static std::string JsonEscape(const std::string &in)
{
    std::string out;
    out.reserve(in.size() + 8);
    for (size_t i = 0; i < in.size(); i++) {
        unsigned char c = static_cast<unsigned char>(in[i]);
        if (c == '"' || c == '\\') {
            out += '\\';
            out += static_cast<char>(c);
        } else if (c < 0x20) {
            char buf[8];
            snprintf(buf, sizeof(buf), "\\u%04x", c);
            out += buf;
        } else {
            out += static_cast<char>(c);
        }
    }
    return out;
}

/* ------------------------------------------------------------------ */
/* SMB (libsmb2)                                                       */
/* ------------------------------------------------------------------ */

struct SmbConn {
    pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
    struct smb2_context *ctx = nullptr;
    struct smb2fh *fh = nullptr;
    std::string server;
    std::string share;
    std::string path;   /* path inside the share, no leading slash */
    std::string user;
    std::string domain;
    std::string password;
    uint64_t size = 0;
    uint64_t mtime = 0;
};

static pthread_mutex_t g_smbMu = PTHREAD_MUTEX_INITIALIZER;
static std::map<int64_t, SmbConn *> g_smbConns;
static int64_t g_smbNextId = 1;

static bool SmbErrLooksAuth(const std::string &err)
{
    return err.find("LOGON") != std::string::npos ||
           err.find("ACCESS_DENIED") != std::string::npos ||
           err.find("access denied") != std::string::npos ||
           err.find("NT_STATUS_WRONG") != std::string::npos ||
           err.find("NT_STATUS_ACCESS") != std::string::npos;
}

static struct smb2_context *SmbConnect(const std::string &server,
                                       const std::string &share,
                                       const std::string &user,
                                       const std::string &domain,
                                       const std::string &password)
{
    struct smb2_context *ctx = smb2_init_context();
    if (ctx == nullptr) {
        throw NetError("SMB_ERROR", "cannot init smb2 context");
    }
    smb2_set_timeout(ctx, 30);
    if (!user.empty()) {
        smb2_set_user(ctx, user.c_str());
    }
    if (!password.empty()) {
        smb2_set_password(ctx, password.c_str());
    }
    if (!domain.empty()) {
        smb2_set_domain(ctx, domain.c_str());
    }
    if (smb2_connect_share(ctx, server.c_str(), share.c_str(), user.c_str()) != 0) {
        std::string err = smb2_get_error(ctx);
        smb2_destroy_context(ctx);
        throw NetError(SmbErrLooksAuth(err) ? "SMB_AUTH" : "SMB_ERROR",
                       err.empty() ? std::string("connect failed") : err);
    }
    return ctx;
}

static struct smb2fh *SmbOpenFile(struct smb2_context *ctx, const std::string &path)
{
    struct smb2fh *fh = smb2_open(ctx, path.c_str(), O_RDONLY);
    if (fh == nullptr) {
        std::string err = smb2_get_error(ctx);
        throw NetError(SmbErrLooksAuth(err) ? "SMB_AUTH" : "SMB_ERROR",
                       err.empty() ? std::string("open failed") : err);
    }
    return fh;
}

static void SmbConnDestroy(SmbConn *conn)
{
    if (conn->ctx != nullptr) {
        if (conn->fh != nullptr) {
            smb2_close(conn->ctx, conn->fh);
            conn->fh = nullptr;
        }
        smb2_disconnect_share(conn->ctx);
        smb2_destroy_context(conn->ctx);
        conn->ctx = nullptr;
    }
}

static SmbConn *SmbConnFind(int64_t handle)
{
    SmbConn *conn = nullptr;
    pthread_mutex_lock(&g_smbMu);
    std::map<int64_t, SmbConn *>::iterator it = g_smbConns.find(handle);
    if (it != g_smbConns.end()) {
        conn = it->second;
    }
    pthread_mutex_unlock(&g_smbMu);
    return conn;
}

/* smbOpenAsync(server, share, path, user, domain, password) ->
 * {"handle":N,"size":S,"mtime":M} */
static napi_value SmbOpenAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 6;
    napi_value args[6];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 6) {
        napi_throw_type_error(env, nullptr, "smbOpenAsync(server, share, path, user, domain, password) required");
        return nullptr;
    }

    auto *conn = new SmbConn();
    conn->server = GetStrArg(env, args[0]);
    conn->share = GetStrArg(env, args[1]);
    conn->path = GetStrArg(env, args[2]);
    conn->user = GetStrArg(env, args[3]);
    conn->domain = GetStrArg(env, args[4]);
    conn->password = GetStrArg(env, args[5]);

    auto *job = new NetJob();
    job->run = [conn, job]() {
        pthread_mutex_lock(&conn->mu);
        try {
            conn->ctx = SmbConnect(conn->server, conn->share, conn->user, conn->domain, conn->password);
            conn->fh = SmbOpenFile(conn->ctx, conn->path);
            struct smb2_stat_64 st;
            memset(&st, 0, sizeof(st));
            if (smb2_fstat(conn->ctx, conn->fh, &st) != 0) {
                std::string err = smb2_get_error(conn->ctx);
                throw NetError("SMB_ERROR", err.empty() ? std::string("fstat failed") : err);
            }
            conn->size = st.smb2_size;
            conn->mtime = st.smb2_mtime;
        } catch (...) {
            pthread_mutex_unlock(&conn->mu);
            throw;
        }
        pthread_mutex_unlock(&conn->mu);

        int64_t id = 0;
        pthread_mutex_lock(&g_smbMu);
        id = g_smbNextId++;
        g_smbConns[id] = conn;
        pthread_mutex_unlock(&g_smbMu);

        char buf[160];
        snprintf(buf, sizeof(buf), "{\"handle\":%lld,\"size\":%llu,\"mtime\":%llu}",
                 static_cast<long long>(id), static_cast<unsigned long long>(conn->size),
                 static_cast<unsigned long long>(conn->mtime));
        job->result = buf;
    };
    return StartNetJob(env, job, "smb_open");
}

/* smbReadAtAsync(handle, offset, len) -> ArrayBuffer.
 * Fills exactly len bytes unless EOF cuts it short; retries once through a
 * fresh connection on error. */
static napi_value SmbReadAtAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "smbReadAtAsync(handle, offset, len) required");
        return nullptr;
    }
    int64_t handle = GetIntArg(env, args[0]);
    int64_t offset = GetIntArg(env, args[1]);
    int64_t len = GetIntArg(env, args[2]);
    if (len <= 0 || len > 8 * 1024 * 1024) {
        napi_throw_type_error(env, nullptr, "len out of range");
        return nullptr;
    }

    auto *job = new NetJob();
    job->isBin = true;
    job->bin.resize(static_cast<size_t>(len));
    job->run = [handle, offset, len, job]() {
        SmbConn *conn = SmbConnFind(handle);
        if (conn == nullptr) {
            throw NetError("SMB_ERROR", "connection not found (closed?)");
        }
        bool err = false;
        size_t got = 0;
        pthread_mutex_lock(&conn->mu);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (conn->ctx == nullptr || conn->fh == nullptr) {
                    SmbConnDestroy(conn);
                    conn->ctx = SmbConnect(conn->server, conn->share, conn->user, conn->domain, conn->password);
                    conn->fh = SmbOpenFile(conn->ctx, conn->path);
                }
                uint32_t maxRead = smb2_get_max_read_size(conn->ctx);
                if (maxRead == 0) {
                    maxRead = 1024 * 1024;
                }
                uint64_t off = static_cast<uint64_t>(offset);
                got = 0;
                err = false;
                bool eof = false;
                while (got < static_cast<size_t>(len)) {
                    uint32_t chunk = static_cast<uint32_t>(len - got);
                    if (chunk > maxRead) {
                        chunk = maxRead;
                    }
                    int64_t n = smb2_pread(conn->ctx, conn->fh, job->bin.data() + got, chunk, off);
                    if (n < 0) {
                        err = true;
                        break;
                    }
                    if (n == 0) {
                        eof = true;
                        break;
                    }
                    got += static_cast<size_t>(n);
                    off += static_cast<uint64_t>(n);
                }
                if (eof || (!err && got == static_cast<size_t>(len))) {
                    err = false;
                    break;
                }
                if (err) {
                    SmbConnDestroy(conn); /* force reconnect for the next attempt */
                }
            } catch (...) {
                pthread_mutex_unlock(&conn->mu);
                throw;
            }
        }
        pthread_mutex_unlock(&conn->mu);
        if (err && got < static_cast<size_t>(len)) {
            throw NetError("SMB_ERROR", "read failed after retry");
        }
        job->bin.resize(got);
    };
    return StartNetJob(env, job, "smb_read");
}

/* smbCloseAsync(handle) -> "ok" */
static napi_value SmbCloseAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    int64_t handle = GetIntArg(env, args[0]);

    auto *job = new NetJob();
    job->run = [handle, job]() {
        pthread_mutex_lock(&g_smbMu);
        std::map<int64_t, SmbConn *>::iterator it = g_smbConns.find(handle);
        SmbConn *conn = (it != g_smbConns.end()) ? it->second : nullptr;
        g_smbConns.erase(it);
        pthread_mutex_unlock(&g_smbMu);
        if (conn != nullptr) {
            pthread_mutex_lock(&conn->mu);
            SmbConnDestroy(conn);
            pthread_mutex_unlock(&conn->mu);
            delete conn;
        }
        job->result = "ok";
    };
    return StartNetJob(env, job, "smb_close");
}

/* smbListAsync(server, share, path, user, domain, password) ->
 * [{"name":"..","isDir":bool,"size":N}, ...] */
static napi_value SmbListAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 6;
    napi_value args[6];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 6) {
        napi_throw_type_error(env, nullptr, "smbListAsync(server, share, path, user, domain, password) required");
        return nullptr;
    }
    std::string server = GetStrArg(env, args[0]);
    std::string share = GetStrArg(env, args[1]);
    std::string path = GetStrArg(env, args[2]);
    std::string user = GetStrArg(env, args[3]);
    std::string domain = GetStrArg(env, args[4]);
    std::string password = GetStrArg(env, args[5]);

    auto *job = new NetJob();
    job->run = [server, share, path, user, domain, password, job]() {
        struct smb2_context *ctx = SmbConnect(server, share, user, domain, password);
        struct smb2dir *dir = nullptr;
        try {
            std::string p = path;
            while (p.size() > 1 && p[0] == '/') {
                p.erase(0, 1);
            }
            dir = smb2_opendir(ctx, p.c_str());
            if (dir == nullptr) {
                std::string err = smb2_get_error(ctx);
                throw NetError(SmbErrLooksAuth(err) ? "SMB_AUTH" : "SMB_ERROR",
                               err.empty() ? std::string("opendir failed") : err);
            }
            std::string json = "[";
            bool first = true;
            struct smb2dirent *ent = nullptr;
            while ((ent = smb2_readdir(ctx, dir)) != nullptr) {
                std::string name = ent->name;
                if (name == "." || name == "..") {
                    continue;
                }
                bool isDir = (ent->st.smb2_type == SMB2_TYPE_DIRECTORY);
                if (!first) {
                    json += ",";
                }
                first = false;
                json += "{\"name\":\"";
                json += JsonEscape(name);
                if (isDir) {
                    json += "\",\"isDir\":true,\"size\":0}";
                } else {
                    json += "\",\"isDir\":false,\"size\":";
                    json += std::to_string(static_cast<unsigned long long>(ent->st.smb2_size));
                    json += "}";
                }
            }
            smb2_closedir(ctx, dir);
            json += "]";
            smb2_disconnect_share(ctx);
            smb2_destroy_context(ctx);
            job->result = json;
        } catch (...) {
            if (dir != nullptr) {
                smb2_closedir(ctx, dir);
            }
            smb2_disconnect_share(ctx);
            smb2_destroy_context(ctx);
            throw;
        }
    };
    return StartNetJob(env, job, "smb_list");
}

/* ------------------------------------------------------------------ */
/* SFTP (libssh2)                                                      */
/* ------------------------------------------------------------------ */

struct SftpConn {
    pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
    int sock = -1;
    LIBSSH2_SESSION *session = nullptr;
    LIBSSH2_SFTP *sftp = nullptr;
    LIBSSH2_SFTP_HANDLE *fh = nullptr;
    std::string host;
    int port = 22;
    std::string user;
    std::string password;
    std::string keyPath;
    std::string keyPass;
    bool trustAll = false;
    std::string fingerprint;  /* SHA256 host key hash (base64, from libssh2) */
    std::string openPath;     /* file opened via sftpOpenAsync, for reconnect */
    uint64_t size = 0;
    uint64_t mtime = 0;
};

static pthread_mutex_t g_sftpMu = PTHREAD_MUTEX_INITIALIZER;
static std::map<int64_t, SftpConn *> g_sftpConns;
static int64_t g_sftpNextId = 1;

static int SftpTcpConnect(const std::string &host, int port)
{
    char portstr[16];
    snprintf(portstr, sizeof(portstr), "%d", port);
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *res = nullptr;
    if (getaddrinfo(host.c_str(), portstr, &hints, &res) != 0 || res == nullptr) {
        throw NetError("SFTP_ERROR", "cannot resolve host " + host);
    }
    int fd = -1;
    for (struct addrinfo *ai = res; ai != nullptr; ai = ai->ai_next) {
        fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) {
            continue;
        }
        int flags = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        int rc = connect(fd, ai->ai_addr, ai->ai_addrlen);
        if (rc != 0 && errno == EINPROGRESS) {
            struct pollfd pfd;
            memset(&pfd, 0, sizeof(pfd));
            pfd.fd = fd;
            pfd.events = POLLOUT;
            if (poll(&pfd, 1, 15000) > 0) {
                int soerr = 0;
                socklen_t slen = sizeof(soerr);
                getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &slen);
                rc = (soerr == 0) ? 0 : -1;
            } else {
                rc = -1;
            }
        }
        if (rc == 0) {
            fcntl(fd, F_SETFL, flags); /* back to blocking for libssh2 */
            break;
        }
        close(fd);
        fd = -1;
    }
    freeaddrinfo(res);
    if (fd < 0) {
        throw NetError("SFTP_ERROR", "cannot connect to " + host);
    }
    return fd;
}

/* session init -> handshake -> TOFU check -> auth -> sftp channel.
 * conn fields (host/user/password/keyPath/keyPass/trustAll/sock) must be set. */
static void SftpHandshakeAuth(SftpConn *conn, const std::string &expectFp)
{
    conn->session = libssh2_session_init();
    if (conn->session == nullptr) {
        throw NetError("SFTP_ERROR", "cannot init ssh session");
    }
    libssh2_session_set_blocking(conn->session, 1);
    libssh2_session_set_timeout(conn->session, 30000);
    int rc = libssh2_session_handshake(conn->session, conn->sock);
    if (rc != 0) {
        char *err = nullptr;
        int errlen = 0;
        libssh2_session_last_error(conn->session, &err, &errlen, 0);
        std::string msg = (err != nullptr && errlen > 0) ? std::string(err, static_cast<size_t>(errlen)) : std::string("handshake failed");
        throw NetError("SFTP_ERROR", msg);
    }

    const char *fp = libssh2_hostkey_hash(conn->session, LIBSSH2_HOSTKEY_HASH_SHA256);
    conn->fingerprint = (fp != nullptr) ? std::string(fp) : std::string();
    if (!conn->trustAll && !expectFp.empty() && !conn->fingerprint.empty() && expectFp != conn->fingerprint) {
        throw NetError("SFTP_HOSTKEY_CHANGED", conn->fingerprint);
    }

    if (!conn->keyPath.empty()) {
        rc = libssh2_userauth_publickey_fromfile(conn->session, conn->user.c_str(), nullptr,
                                                 conn->keyPath.c_str(),
                                                 conn->keyPass.empty() ? nullptr : conn->keyPass.c_str());
    } else {
        rc = libssh2_userauth_password(conn->session, conn->user.c_str(), conn->password.c_str());
    }
    if (rc != 0) {
        char *err = nullptr;
        int errlen = 0;
        libssh2_session_last_error(conn->session, &err, &errlen, 0);
        std::string msg = (err != nullptr && errlen > 0) ? std::string(err, static_cast<size_t>(errlen)) : std::string("auth failed");
        throw NetError(rc == LIBSSH2_ERROR_AUTHENTICATION_FAILED ? "SFTP_AUTH" : "SFTP_ERROR", msg);
    }

    conn->sftp = libssh2_sftp_init(conn->session);
    if (conn->sftp == nullptr) {
        throw NetError("SFTP_ERROR", "cannot init sftp channel");
    }
}

/* Tear down transport (fh/sftp/session/socket). Keeps credential fields. */
static void SftpConnDestroy(SftpConn *conn)
{
    if (conn->fh != nullptr) {
        libssh2_sftp_close(conn->fh);
        conn->fh = nullptr;
    }
    if (conn->sftp != nullptr) {
        libssh2_sftp_shutdown(conn->sftp);
        conn->sftp = nullptr;
    }
    if (conn->session != nullptr) {
        libssh2_session_disconnect(conn->session, "bye");
        libssh2_session_free(conn->session);
        conn->session = nullptr;
    }
    if (conn->sock >= 0) {
        close(conn->sock);
        conn->sock = -1;
    }
}

/* One-shot connect+auth (new SftpConn). Caller registers or destroys it. */
static SftpConn *SftpConnectAndAuth(const std::string &host, int port,
                                    const std::string &user,
                                    const std::string &password,
                                    const std::string &keyPath,
                                    const std::string &keyPass,
                                    bool trustAll,
                                    const std::string &expectFp)
{
    auto *conn = new SftpConn();
    try {
        conn->host = host;
        conn->port = port;
        conn->user = user;
        conn->password = password;
        conn->keyPath = keyPath;
        conn->keyPass = keyPass;
        conn->trustAll = trustAll;
        conn->sock = SftpTcpConnect(host, port);
        SftpHandshakeAuth(conn, expectFp);
        return conn;
    } catch (...) {
        SftpConnDestroy(conn);
        delete conn;
        throw;
    }
}

/* Tear down transport and reconnect under the same credentials (called with
 * conn->mu held). Uses the stored fingerprint for the TOFU check. */
static void SftpReconnectLocked(SftpConn *conn)
{
    std::string fp = conn->fingerprint;
    std::string openPath = conn->openPath;
    SftpConnDestroy(conn);
    conn->sock = SftpTcpConnect(conn->host, conn->port);
    SftpHandshakeAuth(conn, fp);
    if (!openPath.empty()) {
        conn->fh = libssh2_sftp_open(conn->sftp, openPath.c_str(), LIBSSH2_FXF_READ, 0);
        if (conn->fh == nullptr) {
            throw NetError("SFTP_ERROR", "cannot reopen file after reconnect");
        }
    }
}

static SftpConn *SftpConnFind(int64_t handle)
{
    SftpConn *conn = nullptr;
    pthread_mutex_lock(&g_sftpMu);
    std::map<int64_t, SftpConn *>::iterator it = g_sftpConns.find(handle);
    if (it != g_sftpConns.end()) {
        conn = it->second;
    }
    pthread_mutex_unlock(&g_sftpMu);
    return conn;
}

/* sftpConnectAsync(host, port, user, password, keyPath, keyPass, trustAll,
 * expectFp) -> {"handle":N,"fingerprint":".."} */
static napi_value SftpConnectAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 8;
    napi_value args[8];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 8) {
        napi_throw_type_error(env, nullptr,
            "sftpConnectAsync(host, port, user, password, keyPath, keyPass, trustAll, expectFp) required");
        return nullptr;
    }
    std::string host = GetStrArg(env, args[0]);
    int port = static_cast<int>(GetIntArg(env, args[1]));
    if (port <= 0) {
        port = 22;
    }
    std::string user = GetStrArg(env, args[2]);
    std::string password = GetStrArg(env, args[3]);
    std::string keyPath = GetStrArg(env, args[4]);
    std::string keyPass = GetStrArg(env, args[5]);
    bool trustAll = GetBoolArg(env, args[6]);
    std::string expectFp = GetStrArg(env, args[7]);

    auto *job = new NetJob();
    job->run = [host, port, user, password, keyPath, keyPass, trustAll, expectFp, job]() {
        SftpConn *conn = SftpConnectAndAuth(host, port, user, password, keyPath, keyPass, trustAll, expectFp);
        int64_t id = 0;
        pthread_mutex_lock(&g_sftpMu);
        id = g_sftpNextId++;
        g_sftpConns[id] = conn;
        pthread_mutex_unlock(&g_sftpMu);
        std::string json = "{\"handle\":" + std::to_string(id) +
                           ",\"fingerprint\":\"" + JsonEscape(conn->fingerprint) + "\"}";
        job->result = json;
    };
    return StartNetJob(env, job, "sftp_connect");
}

/* sftpOpenAsync(handle, path) -> {"size":S,"mtime":M} */
static napi_value SftpOpenAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "sftpOpenAsync(handle, path) required");
        return nullptr;
    }
    int64_t handle = GetIntArg(env, args[0]);
    std::string path = GetStrArg(env, args[1]);

    auto *job = new NetJob();
    job->run = [handle, path, job]() {
        SftpConn *conn = SftpConnFind(handle);
        if (conn == nullptr) {
            throw NetError("SFTP_ERROR", "connection not found (closed?)");
        }
        pthread_mutex_lock(&conn->mu);
        try {
            if (conn->fh != nullptr) {
                libssh2_sftp_close(conn->fh);
                conn->fh = nullptr;
            }
            conn->fh = libssh2_sftp_open(conn->sftp, path.c_str(), LIBSSH2_FXF_READ, 0);
            if (conn->fh == nullptr) {
                char *err = nullptr;
                int errlen = 0;
                libssh2_session_last_error(conn->session, &err, &errlen, 0);
                std::string msg = (err != nullptr && errlen > 0) ? std::string(err, static_cast<size_t>(errlen)) : std::string("open failed");
                unsigned long serr = libssh2_sftp_last_error(conn->sftp);
                /* SSH_FX_PERMISSION_DENIED == 3 */
                throw NetError(serr == 3 ? "SFTP_AUTH" : "SFTP_ERROR", msg);
            }
            conn->openPath = path;
            LIBSSH2_SFTP_ATTRIBUTES attrs;
            memset(&attrs, 0, sizeof(attrs));
            if (libssh2_sftp_fstat(conn->fh, &attrs) != 0) {
                throw NetError("SFTP_ERROR", "cannot stat remote file");
            }
            conn->size = attrs.filesize;
            conn->mtime = attrs.mtime;
        } catch (...) {
            pthread_mutex_unlock(&conn->mu);
            throw;
        }
        pthread_mutex_unlock(&conn->mu);
        char buf[128];
        snprintf(buf, sizeof(buf), "{\"size\":%llu,\"mtime\":%lu}",
                 static_cast<unsigned long long>(conn->size), static_cast<unsigned long>(conn->mtime));
        job->result = buf;
    };
    return StartNetJob(env, job, "sftp_open");
}

/* sftpReadAtAsync(handle, offset, len) -> ArrayBuffer.
 * Fills exactly len bytes unless EOF cuts it short; retries once through a
 * fresh connection on error. */
static napi_value SftpReadAtAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "sftpReadAtAsync(handle, offset, len) required");
        return nullptr;
    }
    int64_t handle = GetIntArg(env, args[0]);
    int64_t offset = GetIntArg(env, args[1]);
    int64_t len = GetIntArg(env, args[2]);
    if (len <= 0 || len > 8 * 1024 * 1024) {
        napi_throw_type_error(env, nullptr, "len out of range");
        return nullptr;
    }

    auto *job = new NetJob();
    job->isBin = true;
    job->bin.resize(static_cast<size_t>(len));
    job->run = [handle, offset, len, job]() {
        SftpConn *conn = SftpConnFind(handle);
        if (conn == nullptr) {
            throw NetError("SFTP_ERROR", "connection not found (closed?)");
        }
        bool err = false;
        size_t got = 0;
        pthread_mutex_lock(&conn->mu);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt > 0) {
                    SftpReconnectLocked(conn);
                }
                if (conn->fh == nullptr) {
                    throw NetError("SFTP_ERROR", "no file open on connection");
                }
                libssh2_sftp_seek64(conn->fh, static_cast<uint64_t>(offset));
                got = 0;
                err = false;
                bool eof = false;
                while (got < static_cast<size_t>(len)) {
                    ssize_t n = libssh2_sftp_read(conn->fh,
                                                  reinterpret_cast<char *>(job->bin.data()) + got,
                                                  static_cast<size_t>(len) - got);
                    if (n < 0) {
                        err = true;
                        break;
                    }
                    if (n == 0) {
                        eof = true;
                        break;
                    }
                    got += static_cast<size_t>(n);
                }
                if (eof || (!err && got == static_cast<size_t>(len))) {
                    err = false;
                    break;
                }
            } catch (...) {
                pthread_mutex_unlock(&conn->mu);
                throw;
            }
        }
        pthread_mutex_unlock(&conn->mu);
        if (err && got < static_cast<size_t>(len)) {
            throw NetError("SFTP_ERROR", "read failed after retry");
        }
        job->bin.resize(got);
    };
    return StartNetJob(env, job, "sftp_read");
}

/* sftpListAsync(host, port, user, password, keyPath, keyPass, trustAll,
 * expectFp, path) -> [{"name":"..","isDir":bool,"size":N}, ...] */
static napi_value SftpListAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 9;
    napi_value args[9];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 9) {
        napi_throw_type_error(env, nullptr,
            "sftpListAsync(host, port, user, password, keyPath, keyPass, trustAll, expectFp, path) required");
        return nullptr;
    }
    std::string host = GetStrArg(env, args[0]);
    int port = static_cast<int>(GetIntArg(env, args[1]));
    if (port <= 0) {
        port = 22;
    }
    std::string user = GetStrArg(env, args[2]);
    std::string password = GetStrArg(env, args[3]);
    std::string keyPath = GetStrArg(env, args[4]);
    std::string keyPass = GetStrArg(env, args[5]);
    bool trustAll = GetBoolArg(env, args[6]);
    std::string expectFp = GetStrArg(env, args[7]);
    std::string path = GetStrArg(env, args[8]);

    auto *job = new NetJob();
    job->run = [host, port, user, password, keyPath, keyPass, trustAll, expectFp, path, job]() {
        SftpConn *conn = SftpConnectAndAuth(host, port, user, password, keyPath, keyPass, trustAll, expectFp);
        std::string json;
        std::string dir = path;
        if (dir.empty()) {
            /* '' means the user's home directory; resolve it to an absolute
             * path so child paths (home + '/' + name) stay valid */
            char home[1024];
            memset(home, 0, sizeof(home));
            if (libssh2_sftp_realpath(conn->sftp, ".", home, sizeof(home)) == 0 && home[0] != '\0') {
                dir = home;
            } else {
                dir = ".";
            }
        }
        try {
            LIBSSH2_SFTP_HANDLE *dh = libssh2_sftp_opendir(conn->sftp, dir.c_str());
            if (dh == nullptr) {
                char *err = nullptr;
                int errlen = 0;
                libssh2_session_last_error(conn->session, &err, &errlen, 0);
                std::string msg = (err != nullptr && errlen > 0) ? std::string(err, static_cast<size_t>(errlen)) : std::string("opendir failed");
                unsigned long serr = libssh2_sftp_last_error(conn->sftp);
                throw NetError(serr == 3 ? "SFTP_AUTH" : "SFTP_ERROR", msg);
            }
            json = "[";
            bool first = true;
            char name[1024];
            LIBSSH2_SFTP_ATTRIBUTES attrs;
            for (;;) {
                memset(&attrs, 0, sizeof(attrs));
                ssize_t n = libssh2_sftp_readdir(dh, name, sizeof(name), &attrs);
                if (n <= 0) {
                    break;
                }
                std::string nm(name, static_cast<size_t>(n));
                if (nm == "." || nm == "..") {
                    continue;
                }
                bool isDir = LIBSSH2_SFTP_S_ISDIR(attrs.permissions);
                if (!first) {
                    json += ",";
                }
                first = false;
                json += "{\"name\":\"";
                json += JsonEscape(nm);
                if (isDir) {
                    json += "\",\"isDir\":true,\"size\":0}";
                } else {
                    json += "\",\"isDir\":false,\"size\":";
                    json += std::to_string(static_cast<unsigned long long>(
                        (attrs.flags & LIBSSH2_SFTP_ATTR_SIZE) ? attrs.filesize : 0));
                    json += "}";
                }
            }
            libssh2_sftp_closedir(dh);
            json += "]";
        } catch (...) {
            SftpConnDestroy(conn);
            delete conn;
            throw;
        }
        SftpConnDestroy(conn);
        delete conn;
        job->result = json;
    };
    return StartNetJob(env, job, "sftp_list");
}

/* sftpHomeAsync(handle) -> "/home/<user>" (realpath of "."), for scanners. */
static napi_value SftpHomeAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    int64_t handle = GetIntArg(env, args[0]);

    auto *job = new NetJob();
    job->run = [handle, job]() {
        SftpConn *conn = SftpConnFind(handle);
        if (conn == nullptr) {
            throw NetError("SFTP_ERROR", "connection not found (closed?)");
        }
        pthread_mutex_lock(&conn->mu);
        char home[1024];
        memset(home, 0, sizeof(home));
        if (libssh2_sftp_realpath(conn->sftp, ".", home, sizeof(home)) != 0 || home[0] == '\0') {
            pthread_mutex_unlock(&conn->mu);
            throw NetError("SFTP_ERROR", "cannot resolve home directory");
        }
        pthread_mutex_unlock(&conn->mu);
        job->result = std::string(home);
    };
    return StartNetJob(env, job, "sftp_home");
}

/* sftpCloseAsync(handle) -> "ok" */
static napi_value SftpCloseAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    int64_t handle = GetIntArg(env, args[0]);

    auto *job = new NetJob();
    job->run = [handle, job]() {
        pthread_mutex_lock(&g_sftpMu);
        std::map<int64_t, SftpConn *>::iterator it = g_sftpConns.find(handle);
        SftpConn *conn = (it != g_sftpConns.end()) ? it->second : nullptr;
        g_sftpConns.erase(it);
        pthread_mutex_unlock(&g_sftpMu);
        if (conn != nullptr) {
            pthread_mutex_lock(&conn->mu);
            SftpConnDestroy(conn);
            pthread_mutex_unlock(&conn->mu);
            delete conn;
        }
        job->result = "ok";
    };
    return StartNetJob(env, job, "sftp_close");
}

/* ------------------------------------------------------------------ */

} // namespace

/* Called from mupdf_napi.cpp Init() to register the SMB/SFTP exports. */
napi_value RegisterRemoteNet(napi_env env, napi_value exports)
{
    napi_property_descriptor desc[] = {
        {"smbOpenAsync", nullptr, SmbOpenAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"smbReadAtAsync", nullptr, SmbReadAtAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"smbCloseAsync", nullptr, SmbCloseAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"smbListAsync", nullptr, SmbListAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"sftpConnectAsync", nullptr, SftpConnectAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"sftpOpenAsync", nullptr, SftpOpenAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"sftpReadAtAsync", nullptr, SftpReadAtAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"sftpListAsync", nullptr, SftpListAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"sftpHomeAsync", nullptr, SftpHomeAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"sftpCloseAsync", nullptr, SftpCloseAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
