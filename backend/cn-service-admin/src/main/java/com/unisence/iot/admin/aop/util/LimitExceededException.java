package com.unisence.iot.admin.aop.util;

import java.io.IOException;

class LimitExceededException extends IOException {
    LimitExceededException() {
        super("limit exceeded");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; // skip stack trace capture — this is a control-flow signal, not a real error
    }
}
