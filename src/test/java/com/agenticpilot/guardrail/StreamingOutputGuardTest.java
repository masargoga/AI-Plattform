package com.agenticpilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StreamingOutputGuardTest {

    private static AgentGuardContext ctx(int max, PersonalDataMode pii) {
        return new AgentGuardContext(pii, SpecialDataMode.BLOCK, Set.of(), max, true, List.of());
    }

    /** Feeds the answer in pieces of the given size and returns everything that was released. */
    private static String stream(String answer, int pieceSize, AgentGuardContext ctx, List<String> notes, List<String> released) {
        StreamingOutputGuard guard = new StreamingOutputGuard(ctx, notes, List.of("Answer from docs"));
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < answer.length(); i += pieceSize) {
            String out = guard.accept(answer.substring(i, Math.min(answer.length(), i + pieceSize)));
            if (!out.isEmpty()) {
                released.add(out);
            }
            all.append(out);
        }
        var result = guard.finish();
        if (!result.tail().isEmpty()) {
            released.add(result.tail());
        }
        all.append(result.tail());
        assertThat(all.toString()).isEqualTo(result.fullText());
        return all.toString();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 50, 1000})
    void releasedTextEqualsTheCompleteAnswerCheckForAnyPieceSize(int pieceSize) {
        StringBuilder list = new StringBuilder("Contact anna@mail.example or +49 170 1234567.\n\n| id | name |\n|---|---|\n");
        for (int i = 1; i <= 8; i++) {
            list.append("| ").append(i).append(" | n").append(i).append(" |\n");
        }
        for (int i = 1; i <= 8; i++) {
            list.append(i).append(". step ").append(i).append('\n');
        }
        list.append("Done.");
        AgentGuardContext c = ctx(5, PersonalDataMode.MASK);
        String streamed = stream(list.toString(), pieceSize, c, List.of("Be careful"), new java.util.ArrayList<>());
        String complete = GuardrailEngine.checkOutput(list.toString().strip(), c, List.of("Be careful")).text();
        assertThat(streamed).isEqualTo(complete);
    }

    @Test
    void nothingOfALineLeavesBeforeTheLineIsComplete() {
        StreamingOutputGuard guard = new StreamingOutputGuard(ctx(50, PersonalDataMode.MASK), List.of(), List.of());
        assertThat(guard.accept("Write to anna@mail")).isEmpty();
        assertThat(guard.accept(".example now")).isEmpty();
        assertThat(guard.accept("\nnext")).isEqualTo("Write to [email] now");
        assertThat(guard.finish().fullText()).isEqualTo("Write to [email] now\nnext");
    }

    @Test
    void personalDataNeverAppearsInAnyReleasedPieceEvenWhenSplitMidAddress() {
        List<String> pieces = new java.util.ArrayList<>();
        stream("Mail anna.keller@mail.example today\nand DE89 3704 0044 0532 0130 00 too", 2, ctx(50, PersonalDataMode.MASK), List.of(), pieces);
        assertThat(String.join("", pieces)).doesNotContain("anna").doesNotContain("DE89").contains("[email]").contains("[IBAN]");
    }

    @Test
    void skillLineIsRemovedAndMatchedToKnownSkills() {
        StreamingOutputGuard guard = new StreamingOutputGuard(ctx(50, PersonalDataMode.MASK), List.of(), List.of("Answer from docs"));
        guard.accept("The answer.\nSkill used: Answer from docs");
        var result = guard.finish();
        assertThat(result.fullText()).isEqualTo("The answer.");
        assertThat(result.skillName()).isEqualTo("Answer from docs");
    }

    @Test
    void recordLimitCutsStreamedListsAndSaysSo() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("- item ").append(i).append('\n');
        }
        StreamingOutputGuard guard = new StreamingOutputGuard(ctx(10, PersonalDataMode.MASK), List.of(), List.of());
        String first = guard.accept(sb.toString());
        assertThat(first).contains("- item 10").doesNotContain("- item 11");
        var result = guard.finish();
        assertThat(result.fullText()).endsWith("(Showing the first 10 records.)");
        assertThat(result.decisions()).extracting(Decision::kind).containsExactly(Decision.Kind.RECORD_LIMIT);
    }

    @Test
    void allowedPersonalDataPassesAndNoDecisionIsReported() {
        var released = new java.util.ArrayList<String>();
        String out = stream("anna@mail.example\n", 4, ctx(50, PersonalDataMode.ALLOW), List.of(), released);
        assertThat(out).isEqualTo("anna@mail.example");
    }
}
