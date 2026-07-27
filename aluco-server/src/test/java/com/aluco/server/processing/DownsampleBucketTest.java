package com.aluco.server.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bucket math behind the down-sampling SQL (spec 5.5 API #7 / 7.4.5). */
class DownsampleBucketTest {

    @Test
    void intervalTokensMapToBucketSeconds() {
        assertThat(MySqlTimeSeriesStore.bucketSeconds("1m")).isEqualTo(60);
        assertThat(MySqlTimeSeriesStore.bucketSeconds("5m")).isEqualTo(300);
        assertThat(MySqlTimeSeriesStore.bucketSeconds("1h")).isEqualTo(3600);
        assertThat(MySqlTimeSeriesStore.bucketSeconds("1d")).isEqualTo(86400);
    }

    @Test
    void unknownIntervalRejected() {
        assertThatThrownBy(() -> MySqlTimeSeriesStore.bucketSeconds("7m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bucketComputationMatchesSqlSemantics() {
        // SQL: FLOOR(UNIX_TIMESTAMP(ts)/bucket) * bucket — verify the grouping
        // assigns adjacent samples of the same window into one bucket.
        long bucket = MySqlTimeSeriesStore.bucketSeconds("5m");
        long ts1 = 1_752_739_200L;      // some epoch second
        long ts2 = ts1 + 299;           // inside the same 5m window
        long ts3 = ts1 - (ts1 % bucket) + bucket; // first second of the next window

        long b1 = (long) Math.floor(ts1 / (double) bucket) * bucket;
        long b2 = (long) Math.floor(ts2 / (double) bucket) * bucket;
        long b3 = (long) Math.floor(ts3 / (double) bucket) * bucket;

        assertThat(b1).isEqualTo(b2);
        assertThat(b3).isEqualTo(b1 + bucket);
        assertThat(b1 % bucket).isZero();
    }
}