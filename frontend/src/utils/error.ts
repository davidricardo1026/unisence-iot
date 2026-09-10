export function getErrorMessage(error: unknown, fallback = '操作失败'): string {
    if (error instanceof Error && error.message) return error.message
    if (typeof error === 'string' && error !== 'cancel') return error
    return fallback
}

export function isCancelError(error: unknown): boolean {
    return error === 'cancel' || (error instanceof Error && error.message === 'cancel')
}
