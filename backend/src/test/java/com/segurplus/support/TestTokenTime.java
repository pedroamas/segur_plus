package com.segurplus.support;

import com.segurplus.auth.TokenTime;
import java.time.Duration;
import java.time.Instant;

/** Reloj de sistema «avanzable» para probar la caducidad del JWT y del refresh sin esperar. */
public class TestTokenTime extends TokenTime {

    private volatile Duration offset = Duration.ZERO;

    @Override
    public Instant now() {
        return super.now().plus(offset);
    }

    public void advance(Duration d) {
        offset = offset.plus(d);
    }

    public void reset() {
        offset = Duration.ZERO;
    }
}
