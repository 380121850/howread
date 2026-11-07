/* v0.60 批次二：取块策略（RemoteOpenCache v0.35/v0.54 同款）——
 * 连续 2 次失败熔断停网 60s，60s 后半开试探；单次取块带重试退避（预算 2s）。
 * 线程安全（块缓存前台读与后台预取并发取同一 fetcher）。 */
#ifndef HRFETCH_H
#define HRFETCH_H

#include <QByteArray>
#include <QMutex>
#include <functional>

namespace hr
{

class FetchPolicy
{
public:
    FetchPolicy(int retryN = 3, int retryMs = 100) : m_retryN(retryN), m_retryMs(retryMs) {}

    /* raw=单次网络取块（pos,len）；返回块数据，失败/熔断中返回空。 */
    QByteArray fetch(const std::function<QByteArray(long long, int)> &raw, long long pos, int len,
                     const char *evtTag = "fetch");

    bool offline() const
    {
        m_mu.lock();
        const bool o = m_offline;
        m_mu.unlock();
        return o;
    }

private:
    mutable QMutex m_mu;
    int m_retryN, m_retryMs;
    int m_consecFail = 0;
    bool m_offline = false;
    long long m_offlineAt = 0;
};

} // namespace hr

#endif /* HRFETCH_H */
