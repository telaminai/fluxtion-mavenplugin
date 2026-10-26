package com.fluxtion.maven;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class UsageSummaryTest {

    @Test
    public void aCompilerWithPlansGivesThePlanBothBalancesAndTheLimits() {
        List<String> lines = UsageSummary.lines(Json.object(StubCompilerServer.PLANS_USAGE));
        Assert.assertEquals("Plan: Free; compile credits available: 250; interpret credits available: 40.",
                lines.get(0));
        Assert.assertEquals("Plan limits: 20 nodes, 5 event types, targets Java.", lines.get(1));
        Assert.assertEquals(2, lines.size());
    }

    @Test
    public void anOlderCompilerStillWorks_noInterpretFigureAndNoLimits() {
        List<String> lines = UsageSummary.lines(Json.object(StubCompilerServer.OLD_USAGE));
        Assert.assertEquals(1, lines.size());
        Assert.assertEquals("Plan: Free; compile credits available: 250.", lines.get(0));
    }

    @Test
    public void proTargetsAndOverridesAreNamed() {
        List<String> lines = UsageSummary.lines(Json.object("{\"plan\":\"pro\",\"available\":900,"
                + "\"kinds\":{\"interpret\":{\"available\":0}},"
                + "\"capabilities\":{\"plan\":\"pro\",\"maxNodes\":1000,\"maxEventTypes\":50,"
                + "\"targets\":[\"java\",\"cpp\"],\"overrides\":{\"maxNodes\":\"1000\"}}}"));
        Assert.assertEquals("Plan: Pro; compile credits available: 900; interpret credits available: 0.", lines.get(0));
        Assert.assertEquals("Plan limits: 1000 nodes, 50 event types, targets Java, C++ (with account overrides).",
                lines.get(1));
    }

    @Test
    public void missingOrOddFieldsSayUnknownAndServerTextIsMadePrintable() {
        List<String> lines = UsageSummary.lines(Json.object("{\"plan\":\"te\\u001b[2Jam\",\"kinds\":{\"interpret\":{}},"
                + "\"capabilities\":{}}"));
        Assert.assertEquals(1, lines.size());
        Assert.assertEquals("Plan: Te?[2Jam; compile credits available: unknown; interpret credits available: unknown.",
                lines.get(0));
        Assert.assertEquals("Plan: unknown; compile credits available: unknown.",
                UsageSummary.lines(Json.object("{}")).get(0));
    }
}
