const INVISIBLE_UNICODE = /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}\p{Default_Ignorable_Code_Point}]/u

export function containsInvisibleUnicode(value: string): boolean {
    return INVISIBLE_UNICODE.test(value)
}

export function rejectInvisibleUnicode(
    _rule: unknown,
    value: unknown,
    callback: (error?: Error) => void
): void {
    if (typeof value === 'string' && containsInvisibleUnicode(value)) {
        callback(new Error('不能包含零宽字符或其他不可见 Unicode 字符'))
        return
    }
    callback()
}
