package com.agenticpilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GuardrailLogicTest {

    private static GuardrailSettings recommended() {
        return new GuardrailSettings(PersonalDataMode.MASK, SpecialDataMode.BLOCK, Region.EU, Retention.DAYS_90, true,
                EnumSet.of(ApprovalAction.EMAIL), "Answer customer questions.");
    }

    @Test
    void recommendedSettingsScoreSevenAndGreen() {
        GuardrailScore s = GuardrailScore.of(recommended());
        assertThat(s.met()).isEqualTo(7);
        assertThat(s.gaps()).isEmpty();
        assertThat(s.level()).isEqualTo(GuardrailScore.Level.GREEN);
    }

    @Test
    void eachMissingControlIsListedAndLowersTheScore() {
        var s = new GuardrailSettings(PersonalDataMode.ALLOW, SpecialDataMode.ALLOW, Region.ANY, Retention.UNLIMITED, false, Set.of(), "  ");
        GuardrailScore score = GuardrailScore.of(s);
        assertThat(score.met()).isZero();
        assertThat(score.gaps()).containsExactly(Control.values());
        assertThat(score.level()).isEqualTo(GuardrailScore.Level.RED);
    }

    @Test
    void fiveAndSixAreAmberAndFourIsRed() {
        var six = new GuardrailSettings(PersonalDataMode.MASK, SpecialDataMode.BLOCK, Region.EU, Retention.DAYS_90, true, Set.of(), "p");
        assertThat(GuardrailScore.of(six).met()).isEqualTo(6);
        assertThat(GuardrailScore.of(six).level()).isEqualTo(GuardrailScore.Level.AMBER);
        var five = new GuardrailSettings(PersonalDataMode.MASK, SpecialDataMode.BLOCK, Region.EU, Retention.DAYS_90, false, Set.of(), "p");
        assertThat(GuardrailScore.of(five).level()).isEqualTo(GuardrailScore.Level.AMBER);
        var four = new GuardrailSettings(PersonalDataMode.MASK, SpecialDataMode.BLOCK, Region.EU, Retention.UNLIMITED, false, Set.of(), "p");
        assertThat(GuardrailScore.of(four).met()).isEqualTo(4);
        assertThat(GuardrailScore.of(four).level()).isEqualTo(GuardrailScore.Level.RED);
    }

    @Test
    void reviewCountsAsMetForSpecialCategories() {
        var s = new GuardrailSettings(PersonalDataMode.BLOCK, SpecialDataMode.REVIEW, Region.EU, Retention.NONE, true, Set.of(ApprovalAction.SHARE), "p");
        assertThat(GuardrailScore.of(s).met()).isEqualTo(7);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "lawsuit, sue, legal advice | Can I sue you over this? | true",
            "lawsuit, sue, legal advice | I will pursue this | false",
            "lawsuit, sue, legal advice | LAWSUIT incoming | true",
            "discount | discounts are great | false",
            "/KD-\\d{4,}/ | My number is KD-48213 | true",
            "/KD-\\d{4,}/ | My number is KD-12 | false",
            "/kd-\\d+/ | KD-7 | true",
            "a.b | axb | false",
            "a.b | a.b | true"})
    void catchPatternsMatchWholeWordsAndSlashedPatternsCaseInsensitively(String catches, String text, boolean expected) {
        assertThat(CatchPattern.parse(catches).orElseThrow().matches(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"''", "' , '", "/(unclosed/", "/[/"})
    void invalidOrEmptyPatternsAreRejected(String catches) {
        assertThat(CatchPattern.parse(catches)).isEmpty();
    }

    @Test
    void maskReplacesEveryMatch() {
        var p = CatchPattern.parse("/KD-\\d{4,}/").orElseThrow();
        assertThat(p.replaceAll("KD-1234 and KD-99999 but not KD-1", "[hidden]")).isEqualTo("[hidden] and [hidden] but not KD-1");
    }

    @Test
    void replacementTextIsNeverInterpretedAsRegexReplacement() {
        var p = CatchPattern.parse("secret").orElseThrow();
        assertThat(p.replaceAll("a secret b", "$1\\")).isEqualTo("a $1\\ b");
    }

    @Test
    void catastrophicPatternsTimeOutInsteadOfHanging() {
        var p = CatchPattern.parse("/(a+)+$/").orElseThrow();
        String evil = "a".repeat(40) + "!";
        long start = System.nanoTime();
        assertThat(p.matches(evil)).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void ruleCheckDescribesTheOutcome() {
        assertThat(RuleCheck.evaluate("", RuleAction.BLOCK, "", "x").kind()).isEqualTo(RuleCheck.Kind.NO_CATCHES);
        assertThat(RuleCheck.evaluate("/[/", RuleAction.BLOCK, "", "x").kind()).isEqualTo(RuleCheck.Kind.INVALID_PATTERN);
        assertThat(RuleCheck.evaluate("sue", RuleAction.BLOCK, "", " ").kind()).isEqualTo(RuleCheck.Kind.NO_SAMPLE);
        assertThat(RuleCheck.evaluate("sue", RuleAction.BLOCK, "", "hello").kind()).isEqualTo(RuleCheck.Kind.NO_MATCH);
        var block = RuleCheck.evaluate("sue", RuleAction.BLOCK, "", "I will sue");
        assertThat(block.kind()).isEqualTo(RuleCheck.Kind.MATCH);
        assertThat(block.text()).isEqualTo("I cannot help with that.");
        assertThat(RuleCheck.evaluate("sue", RuleAction.MASK, "", "I will sue").text()).isEqualTo("I will [hidden]");
        assertThat(RuleCheck.evaluate("sue", RuleAction.NOTE, "Be careful", "I will sue").text()).isEqualTo("Be careful");
    }
}
