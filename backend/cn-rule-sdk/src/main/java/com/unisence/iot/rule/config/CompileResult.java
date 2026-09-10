package com.unisence.iot.rule.config;

import java.util.List;

/**
 * {@code us_iot_rule.compile_result} 的字段级模型：最近一次成功保存时的编译与静态检查摘要。
 *
 * <p>用途是审计与排障——「这条规则上次是用哪个 Groovy 版本、哪套沙箱配置编过的」。
 * 它<b>不参与运行时判定</b>：engine 刷新快照时一律重新编译，不信任库里的这份摘要，
 * 否则换了 Groovy 版本或收紧了沙箱后，旧规则会带着过期的「编译通过」结论继续跑。
 *
 * @param sandboxProfile        沙箱配置指纹，沙箱规则变更时递增，便于批量识别需重新校验的规则
 * @param referencedIdentifiers 静态检查提取出的被引用 identifier，供「物模型删属性时反查受影响规则」使用
 */
public record CompileResult(
    String groovyVersion,
    String scriptSha256,
    long compiledAt,
    int filterScriptChars,
    int outputScriptChars,
    int filterAstNodes,
    int outputAstNodes,
    List<String> referencedIdentifiers,
    String sandboxProfile) {

    public CompileResult {
        referencedIdentifiers = referencedIdentifiers == null
            ? List.of() : List.copyOf(referencedIdentifiers);
    }
}
