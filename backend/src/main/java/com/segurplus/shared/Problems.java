package com.segurplus.shared;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Construcción uniforme de Problem Details (RFC 7807) con ID de correlación. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail, String instance) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("urn:segurplus:problem:" + code));
        pd.setTitle(status.getReasonPhrase());
        if (instance != null) {
            pd.setInstance(URI.create(instance));
        }
        String correlation = Correlation.current();
        if (correlation != null) {
            pd.setProperty("correlationId", correlation);
        }
        return pd;
    }
}
