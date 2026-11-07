#include "hrfetch.h"
#include "hrpaths.h"

#include <QDateTime>
#include <QThread>

namespace hr
{

QByteArray FetchPolicy::fetch(const std::function<QByteArray(long long, int)> &raw, long long pos,
                              int len, const char *evtTag)
{
    m_mu.lock();
    const long long now = QDateTime::currentMSecsSinceEpoch();
    if (m_offline)
    {
        if (now - m_offlineAt < 60000)
        {
            m_mu.unlock();
            return QByteArray(); /* 熔断中 */
        }
        m_offline = false;
        evt("block_cache_halfopen", QString::fromLatin1(evtTag));
    }
    QByteArray got;
    long long spent = 0;
    for (int k = 0; k <= m_retryN; k++)
    {
        m_mu.unlock();
        got = raw(pos, len);
        m_mu.lock();
        if (!got.isEmpty()) break;
        const long long d = (long long)m_retryMs * (k + 1);
        if (k < m_retryN && d > 0 && spent + d <= 2000)
        {
            m_mu.unlock();
            QThread::msleep((unsigned long)d);
            m_mu.lock();
            spent += d;
        }
    }
    if (got.isEmpty())
    {
        if (++m_consecFail >= 2)
        {
            m_offline = true;
            m_offlineAt = QDateTime::currentMSecsSinceEpoch();
            evt("block_cache_offline", QString::fromLatin1(evtTag));
        }
    }
    else
    {
        m_consecFail = 0;
    }
    m_mu.unlock();
    return got;
}

} // namespace hr
