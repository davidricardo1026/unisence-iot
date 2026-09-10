package com.unisence.iot.metadata;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * 设备的运行期标识 {@code productKey + deviceCode}（metadata-sync-bus.md §6.6）。
 *
 * <p>用它而不是 {@code deviceId} 作为缓存键，是因为上行消息里只有这两个字段 ——
 * 热路径不能为了拿到代理主键先查一次库。
 */
public record DeviceRef(String productKey, String deviceCode) {

    /**
     * Redis Hash field 的分隔符，固定为 NUL（{@code 0x00}）。
     *
     * <p>选它是因为它不可能出现在 productKey/deviceCode 中，因此
     * {@code productKey + NUL + deviceCode} 是无歧义拼接：换成 {@code .} 或 {@code :}
     * 这类合法字符，{@code ("ab", "c.d")} 与 {@code ("ab.c", "d")} 会拼出同一个 field。
     */
    private static final char FIELD_SEPARATOR = '\0';

    public DeviceRef {
        if (productKey == null || productKey.isEmpty()) {
            throw new IllegalArgumentException("productKey 不能为空");
        }
        if (deviceCode == null || deviceCode.isEmpty()) {
            throw new IllegalArgumentException("deviceCode 不能为空");
        }
    }

    /**
     * 跨进程稳定的哈希：{@code CRC32C(UTF8(productKey) + 0x00 + UTF8(deviceCode))}。
     *
     * <p><b>绝不能用 {@code String.hashCode()} 或 {@code Objects.hash}</b>：
     * 后者的取值虽在单个 JVM 内稳定，却不是跨版本、跨实现的契约。设备 L2 的 4096 桶与在线状态的
     * 256 shard 都靠这个值定位 —— 一旦滚动升级期间新旧实例算出不同的桶，同一台设备会同时存在
     * 两份缓存条目，谁也淘汰不掉谁。
     *
     * <p>同理禁止大小写折叠与 Unicode 归一化：那会让 {@code A1} 与 {@code a1} 撞进同一个桶，
     * 而它们在数据库里是两台设备。
     *
     * @return 无符号 32 位值（放在 long 的低 32 位）
     */
    public long stableHash() {
        CRC32C crc = new CRC32C();
        crc.update(productKey.getBytes(StandardCharsets.UTF_8));
        crc.update(0);
        crc.update(deviceCode.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /**
     * L2 桶号；桶数必须是 2 的幂，因此用位与代替取模。
     */
    public int bucket(int bucketCount) {
        return (int) (stableHash() & (bucketCount - 1L));
    }

    /**
     * Redis Hash 内的 field 名。
     */
    public String hashField() {
        return productKey + FIELD_SEPARATOR + deviceCode;
    }

    /**
     * Kafka 分区键与日志展示用；与 {@code DeviceMessage.deviceKey()} 一致。
     */
    public String deviceKey() {
        return productKey + '.' + deviceCode;
    }
}
