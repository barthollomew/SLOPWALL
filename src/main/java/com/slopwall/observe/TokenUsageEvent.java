package com.slopwall.observe;

import jdk.jfr.*;

@Name("com.slopwall.TokenUsage")
@Label("AI Token Usage")
@Category("SLOPWALL")
@StackTrace(false)
public class TokenUsageEvent extends jdk.jfr.Event {
    @Label("Tenant ID")          public String tenantId;
    @Label("Provider")           public String provider;
    @Label("Prompt Tokens")      public int promptTokens;
    @Label("Completion Tokens")  public int completionTokens;
    @Label("Estimated Tokens")   public int estimatedTokens;
}
