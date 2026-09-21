package com.anticheat.core.db

import java.net.InetAddress

/** 一条离线 IP 情报规则：网段 → ASN / 组织 / 国家。 */
class IpIntelRule(
    val cidr: String,
    val asn: Int?,
    val org: String?,
    val country: String?
) {
    override fun toString(): String =
        cidr + (if (asn != null) " asn=" + asn else "") + (if (country != null) " country=" + country else "")
}

/**
 * IP 相关的纯逻辑：地址族、网段归并、离线情报匹配。
 *
 * <h3>为什么这些要自己做而不是查服务</h3>
 * ASN 与国家的权威来源是外部 GeoIP/ASN 数据库，而本项目**禁止任何外联**
 * （旧代码里出现过的"启动时下载 GeoLite"就是被清理掉的噪声源）。
 * 所以这里只做两件可靠的事：
 * 1. **按网段归并地址**（`/24`、`/64`）——封禁与聚合查询都靠它；
 * 2. **按管理员给的网段规则**（`database.ip-intel.rules`）给出 ASN / 国家，
 *    最长前缀优先。要更准就换规则表，而不是让插件去联网。
 *
 * <p>Java 的 [InetAddress] 足够完成这些换算，且天然支持 IPv6，
 * 所以不引第三方 IP 库。</p>
 */
object IpIntel {

    const val FAMILY_V4 = 4
    const val FAMILY_V6 = 6

    /** IPv4 归并网段位长。 */
    const val V4_PREFIX = 24

    /** IPv6 归并网段位长（/64 是一个普通家庭网络的粒度）。 */
    const val V6_PREFIX = 64

    /**
     * 归一化客户端地址字符串。
     *
     * <p>要处理三种脏数据：`/192.168.1.5:53211`（InetSocketAddress 的 toString）、
     * `[2001:db8::1]:53211`（IPv6 带括号）、`fe80::1%eth0`（带 zone id）。
     * 不归一化会让同一个 IP 在库里存成多条，直接破坏"IP 封禁"与"同一 IP 的关联分析"。</p>
     *
     * @return 纯地址串；无法识别时返回 null（调用方应跳过落库，**不要**存原始串）
     */
    @JvmStatic
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        var text = raw.trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null

        if (text.startsWith("/")) text = text.substring(1)
        val zone = text.indexOf('%')
        if (zone > 0) text = text.substring(0, zone)

        // [v6]:port  /  v4:port
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end > 0) text = text.substring(1, end)
        } else {
            val colons = text.count { it == ':' }
            if (colons == 1) {
                // IPv4:port（IPv6 至少有 2 个冒号，所以这里不会误伤）
                text = text.substring(0, text.indexOf(':'))
            }
        }

        return try {
            InetAddress.getByName(text).hostAddress
        } catch (t: Throwable) {
            null
        }
    }

    /** 地址族：4 或 6；无法解析返回 0。 */
    @JvmStatic
    fun familyOf(ip: String): Int {
        val address = addressOf(ip) ?: return 0
        return if (address.address.size == 4) FAMILY_V4 else FAMILY_V6
    }

    /**
     * 把地址归并到网段（IPv4 → /24，IPv6 → /64）。
     *
     * <p>封禁网段与"同一网段有几个账号"这类关联分析都用它。归并是按位与，
     * 不做任何"猜测运营商"的事。</p>
     */
    @JvmStatic
    @JvmOverloads
    fun cidrOf(ip: String, v4Prefix: Int = V4_PREFIX, v6Prefix: Int = V6_PREFIX): String? {
        val address = addressOf(ip) ?: return null
        val bytes = address.address
        val bits = if (bytes.size == 4) v4Prefix.coerceIn(0, 32) else v6Prefix.coerceIn(0, 128)
        val masked = maskBytes(bytes, bits)
        return InetAddress.getByAddress(masked).hostAddress + "/" + bits
    }

    /**
     * 按管理员给的规则表匹配情报，**最长前缀优先**。
     *
     * @return 命中的规则；都没命中返回 null（此时 ASN/国家留空，而不是编一个值）
     */
    @JvmStatic
    fun match(ip: String, rules: List<IpIntelRule>): IpIntelRule? {
        val address = addressOf(ip) ?: return null
        var best: IpIntelRule? = null
        var bestBits = -1
        for (rule in rules) {
            val bits = prefixLength(rule.cidr) ?: continue
            val network = addressOf(rule.cidr.substringBefore('/')) ?: continue
            if (network.address.size != address.address.size) continue
            if (!samePrefix(address.address, network.address, bits)) continue
            if (bits > bestBits) {
                bestBits = bits
                best = rule
            }
        }
        return best
    }

    /** 解析配置里的规则列表（`ip-intel.rules`）。格式非法或网段无效的条目直接跳过。 */
    @JvmStatic
    fun parseRules(raw: Any?): List<IpIntelRule> {
        val list = raw as? List<*> ?: return emptyList()
        val rules = ArrayList<IpIntelRule>(list.size)
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val cidr = map["cidr"]?.toString()?.trim().orEmpty()
            if (cidr.isEmpty()) continue
            val slash = cidr.indexOf('/')
            val networkText = if (slash > 0) cidr.substring(0, slash) else cidr
            if (addressOf(networkText) == null) continue
            val bits = if (slash > 0) cidr.substring(slash + 1).toIntOrNull() else null
            if (slash > 0 && bits == null) continue
            rules.add(
                IpIntelRule(
                    cidr = cidr,
                    asn = map["asn"]?.let { value ->
                        when (value) {
                            is Number -> value.toInt()
                            else -> value.toString().trim().toIntOrNull()
                        }
                    },
                    org = map["org"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    country = map["country"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
        // 前缀长的排前面：match 本身也会比较位长，这里排序只是让排障时输出可读
        return rules.sortedByDescending { prefixLength(it.cidr) ?: 0 }
    }

    /** 网段是否覆盖某个地址（用于 IP 封禁的本地判定，与 SQL 的 `cidr >>= ip` 同义）。 */
    @JvmStatic
    fun contains(cidr: String, ip: String): Boolean {
        val bits = prefixLength(cidr) ?: return false
        val network = addressOf(cidr.substringBefore('/')) ?: return false
        val address = addressOf(ip) ?: return false
        if (network.address.size != address.address.size) return false
        return samePrefix(address.address, network.address, bits)
    }

    private fun prefixLength(cidr: String): Int? {
        val slash = cidr.indexOf('/')
        if (slash <= 0) return null
        val bits = cidr.substring(slash + 1).trim().toIntOrNull() ?: return null
        val network = addressOf(cidr.substring(0, slash)) ?: return null
        val max = if (network.address.size == 4) 32 else 128
        return if (bits in 0..max) bits else null
    }

    /**
     * 解析地址。**必须自己先判"是不是字面量"**：
     * `InetAddress.getByName` 对非字面量会走 DNS 查询——那既是阻塞操作，
     * 更是外联行为(本项目禁止)；而且日志里出现的"未知主机名"会被存成 IP 一样的东西。
     */
    private fun addressOf(text: String): InetAddress? {
        val trimmed = text.trim()
        if (!looksLikeLiteral(trimmed)) return null
        return try {
            InetAddress.getByName(trimmed)
        } catch (t: Throwable) {
            null
        }
    }

    /** 是否形如 IPv4/IPv6 字面量（IPv4 不做「前导零」的严格校验，够用即可）。 */
    private fun looksLikeLiteral(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.contains(':')) {
            // IPv6 至少含一个冒号；再排除明显不是地址的串（例如 "a:b" 这类配置误写）
            return text.count { it == ':' } >= 2 || text.endsWith("::")
        }
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() <= 255
        }
    }

    private fun samePrefix(a: ByteArray, b: ByteArray, bits: Int): Boolean {
        var remaining = bits
        var index = 0
        while (remaining >= 8) {
            if (a[index] != b[index]) return false
            index++
            remaining -= 8
        }
        if (remaining == 0) return true
        val mask = (0xFF shl (8 - remaining)) and 0xFF
        return (a[index].toInt() and mask) == (b[index].toInt() and mask)
    }

    private fun maskBytes(bytes: ByteArray, bits: Int): ByteArray {
        val out = ByteArray(bytes.size)
        var remaining = bits
        for (i in bytes.indices) {
            out[i] = when {
                remaining >= 8 -> bytes[i]
                remaining <= 0 -> 0
                else -> (bytes[i].toInt() and ((0xFF shl (8 - remaining)) and 0xFF)).toByte()
            }
            remaining -= 8
        }
        return out
    }
}
