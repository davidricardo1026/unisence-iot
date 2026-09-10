package com.unisence.iot.admin.aop.util;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

class LimitedWriter extends Writer {

    private final StringWriter buf;
    private final int limit;
    private int written = 0;

    LimitedWriter(int limit) {
        this.buf = new StringWriter(Math.min(limit, 4096));
        this.limit = limit;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        int remaining = limit - written;
        if (remaining <= 0) {
            throw new LimitExceededException();
        }
        int toWrite = Math.min(len, remaining);
        buf.write(cbuf, off, toWrite);
        written += toWrite;
        if (toWrite < len) {
            throw new LimitExceededException();
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    String getWritten() {
        return buf.toString();
    }
}
