package com.slopwall.observe;

import jdk.jfr.*;

@Name("com.slopwall.CacheHit")
@Label("Semantic Cache Hit")
@Category("SLOPWALL")
@StackTrace(false)
public class CacheHitEvent extends jdk.jfr.Event {
    @Label("Tenant ID")         public String tenantId;
    @Label("Cosine Similarity") public float cosineSimilarity;
    @Label("Embed Latency ms")  public long embedLatencyMs;
}
