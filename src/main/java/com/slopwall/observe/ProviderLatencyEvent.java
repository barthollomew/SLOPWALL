package com.slopwall.observe;

import jdk.jfr.*;

@Name("com.slopwall.ProviderLatency")
@Label("Provider Latency")
@Category("SLOPWALL")
@StackTrace(false)
public class ProviderLatencyEvent extends jdk.jfr.Event {
    @Label("Provider")    public String provider;
    @Label("Latency ms")  public long latencyMs;
    @Label("Cache Hit")   public boolean cacheHit;
    @Label("HTTP Status") public int httpStatus;
}
