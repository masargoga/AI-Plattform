package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.runtime.KeywordRetriever;
import com.agenticpilot.runtime.Retriever;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.source.ingest.Chunker;
import com.agenticpilot.source.ingest.IngestionService;
import com.agenticpilot.source.ingest.TextExtractor;
import com.agenticpilot.source.ingest.UploadProperties;
import com.agenticpilot.source.ingest.UploadRejectedException;
import com.agenticpilot.support.AsUser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class IngestionAndRetrievalTest {

    @Autowired IngestionService ingestion;
    @Autowired DataSourceService sources;
    @Autowired AgentService agents;
    @Autowired Retriever retriever;
    @Autowired com.agenticpilot.source.AllowedDomainService allowedDomains;

    private final TextExtractor extractor = new TextExtractor(new UploadProperties(1024 * 1024, 50_000, 3, 1000));

    @AfterEach
    void signOut() {
        AsUser.clear();
    }

    // ----- helpers -----

    static byte[] pdf(String... pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] docx(String documentXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    static String docxBody(String... paragraphs) {
        StringBuilder sb = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for (String p : paragraphs) {
            sb.append("<w:p><w:r><w:t>").append(p).append("</w:t></w:r></w:p>");
        }
        return sb.append("</w:body></w:document>").toString();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ----- extraction -----

    @Test
    void extractsTextFromPdfDocxTextAndMarkdown() throws Exception {
        assertThat(extractor.extract("a.pdf", pdf("Plans are prorated per day.")).text()).contains("Plans are prorated per day.");
        assertThat(extractor.extract("a.docx", docx(docxBody("First paragraph", "Second &amp; third"))).text()).isEqualTo("First paragraph\nSecond & third");
        assertThat(extractor.extract("notes.txt", bytes("Hello\r\nworld")).text()).isEqualTo("Hello\nworld");
        assertThat(extractor.extract("README.MD", bytes("# Title\n\nText")).text()).startsWith("# Title");
    }

    @Test
    void refusesWrongTypesRenamedFilesAndBrokenFiles() throws Exception {
        assertThatThrownBy(() -> extractor.extract("a.exe", bytes("x"))).hasMessage("upload.error.type");
        assertThatThrownBy(() -> extractor.extract("noext", bytes("x"))).hasMessage("upload.error.type");
        assertThatThrownBy(() -> extractor.extract("a.pdf", bytes("just text"))).hasMessage("upload.error.mismatch");
        assertThatThrownBy(() -> extractor.extract("a.docx", bytes("just text"))).hasMessage("upload.error.mismatch");
        assertThatThrownBy(() -> extractor.extract("a.pdf", bytes("%PDF-1.7 broken"))).hasMessage("upload.error.unreadable");
        assertThatThrownBy(() -> extractor.extract("a.docx", docx("<not-xml"))).hasMessage("upload.error.unreadable");
        assertThatThrownBy(() -> extractor.extract("a.txt", new byte[0])).hasMessage("upload.error.empty");
        assertThatThrownBy(() -> extractor.extract("a.txt", bytes("   \n  "))).hasMessage("upload.error.noText");
        assertThatThrownBy(() -> extractor.extract("a.txt", new byte[] {(byte) 0xC3, 0x28})).hasMessage("upload.error.encoding");
    }

    @Test
    void enforcesSizePageAndTextLimits() throws Exception {
        assertThatThrownBy(() -> extractor.extract("a.txt", new byte[1024 * 1024 + 1])).hasMessage("upload.error.tooLarge");
        assertThatThrownBy(() -> extractor.extract("a.pdf", pdf("1", "2", "3", "4"))).hasMessage("upload.error.pages");
        assertThatThrownBy(() -> extractor.extract("a.txt", bytes("x".repeat(60_000)))).hasMessage("upload.error.tooMuchText");
        assertThatThrownBy(() -> extractor.extract("a.docx", docx(docxBody("y".repeat(60_000))))).hasMessage("upload.error.tooMuchText");
    }

    @Test
    void docxWithExternalEntitiesIsNotExpanded() throws Exception {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>";
        // either refused or extracted without the file's content
        try {
            assertThat(extractor.extract("a.docx", docx(xml)).text()).doesNotContain("root:");
        } catch (UploadRejectedException expected) {
            assertThat(expected.getMessage()).startsWith("upload.error");
        }
    }

    @Test
    void zipBombsAreStopped() throws Exception {
        TextExtractor small = new TextExtractor(new UploadProperties(2_000, 1_000_000, 10, 1000));
        String huge = docxBody("a".repeat(500_000));
        byte[] bomb = docx(huge);
        assertThat(bomb.length).isLessThan(2_000); // compresses to almost nothing
        assertThatThrownBy(() -> small.extract("bomb.docx", bomb)).isInstanceOf(UploadRejectedException.class);
    }

    @Test
    void fileNamesLoseTheirDirectories() {
        assertThat(extractor.extract("../../etc/notes.txt", bytes("hello")).title()).isEqualTo("notes.txt");
        assertThat(extractor.extract("C:\\temp\\a.md", bytes("hello")).title()).isEqualTo("a.md");
    }

    // ----- chunking -----

    @Test
    void chunksAreRoughlyEightHundredCharactersAndOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("Sentence number ").append(i).append(" is here. ");
        }
        List<String> chunks = Chunker.split(text.toString());
        assertThat(chunks).hasSizeGreaterThan(5);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(Chunker.SIZE));
        assertThat(chunks.get(0)).endsWith(".");
        // overlap: the next chunk starts with text that the previous one already contained
        String tail = chunks.get(0).substring(chunks.get(0).length() - 60);
        assertThat(chunks.get(1)).satisfiesAnyOf(c -> assertThat(c).containsAnyOf(tail.substring(tail.indexOf(' ') + 1, tail.indexOf(' ') + 10)));
        assertThat(String.join(" ", chunks)).contains("Sentence number 199");
        assertThat(Chunker.split("short")).containsExactly("short");
        assertThat(Chunker.split("   ")).isEmpty();
        assertThat(Chunker.split("x".repeat(2000))).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(Chunker.SIZE));
    }

    // ----- ingestion service and retrieval -----

    @Test
    void uploadsAreIndexedAndOnlyFoundForAgentsThatHaveTheSource() throws Exception {
        AsUser.owner();
        DataSource attached = sources.create("Billing " + System.nanoTime(), SourceKind.UPLOAD, "Billing files");
        DataSource other = sources.create("Other " + System.nanoTime(), SourceKind.UPLOAD, "Other files");
        ingestion.addFile(attached.getId(), "billing.pdf", pdf("Plan changes are prorated for the days left in the billing cycle."));
        ingestion.addFile(other.getId(), "secret.txt", bytes("The prorated secret formula is hidden here."));
        Agent withSource = agents.create("RAG " + System.nanoTime(), "j", List.of(), List.of(attached.getId()));
        Agent without = agents.create("NoRAG " + System.nanoTime(), "j", List.of(), List.of());

        var hits = retriever.retrieve(withSource.getId(), "Can I change my plan mid-cycle? Is it prorated?", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).documentTitle()).isEqualTo("billing.pdf");
        assertThat(hits.get(0).sourceName()).isEqualTo(attached.getName());
        assertThat(hits).noneMatch(h -> h.text().contains("secret formula"));
        assertThat(retriever.retrieve(without.getId(), "prorated plan", 5)).isEmpty();
        assertThat(sources.get(attached.getId()).getItemCount()).isEqualTo(1);
        assertThat(sources.get(attached.getId()).getLastSynced()).isNotNull();
    }

    @Test
    void rankingPrefersMoreMatchingTermsAndTitleHits() {
        var terms = KeywordRetriever.terms("How can I change my billing plan, the plan?");
        assertThat(terms).containsExactly("change", "billing", "plan");
        assertThat(KeywordRetriever.score(terms, "billing plan change", "x")).isGreaterThan(KeywordRetriever.score(terms, "billing only", "x"));
        assertThat(KeywordRetriever.score(terms, "billing", "plan guide")).isGreaterThan(KeywordRetriever.score(terms, "billing", "guide"));
        assertThat(KeywordRetriever.terms("is it? the a")).isEmpty();
    }

    @Test
    void likeWildcardsInQuestionsAreTreatedAsPlainText() throws Exception {
        AsUser.owner();
        DataSource s = sources.create("Wild " + System.nanoTime(), SourceKind.UPLOAD, "files");
        ingestion.addFile(s.getId(), "wild.txt", bytes("Discount is 100% off for members only."));
        Agent a = agents.create("Wild " + System.nanoTime(), "j", List.of(), List.of(s.getId()));
        assertThat(retriever.retrieve(a.getId(), "100% off members", 3)).hasSize(1);
        assertThat(retriever.retrieve(a.getId(), "%%% ___", 3)).isEmpty();
    }

    @Test
    void sameContentIsRefusedAndSameNameReplaces() throws Exception {
        AsUser.owner();
        DataSource s = sources.create("Dup " + System.nanoTime(), SourceKind.UPLOAD, "files");
        ingestion.addFile(s.getId(), "a.txt", bytes("Version one of the text"));
        assertThatThrownBy(() -> ingestion.addFile(s.getId(), "b.txt", bytes("Version one of the text"))).hasMessage("upload.error.duplicate");
        ingestion.addFile(s.getId(), "A.txt", bytes("Version two of the text"));
        assertThat(ingestion.documents(s.getId())).hasSize(1);
        Agent a = agents.create("Dup " + System.nanoTime(), "j", List.of(), List.of(s.getId()));
        assertThat(retriever.retrieve(a.getId(), "version", 5)).allMatch(h -> h.text().contains("two"));
    }

    @Test
    void removingADocumentRemovesItsChunks() throws Exception {
        AsUser.owner();
        DataSource s = sources.create("Rm " + System.nanoTime(), SourceKind.UPLOAD, "files");
        ingestion.addFile(s.getId(), "gone.txt", bytes("Findable content about zebras"));
        Agent a = agents.create("Rm " + System.nanoTime(), "j", List.of(), List.of(s.getId()));
        assertThat(retriever.retrieve(a.getId(), "zebras", 3)).hasSize(1);
        UUID doc = ingestion.documents(s.getId()).get(0).getId();
        ingestion.removeDocument(doc);
        assertThat(retriever.retrieve(a.getId(), "zebras", 3)).isEmpty();
        assertThat(sources.get(s.getId()).getItemCount()).isZero();
    }

    @Test
    void onlyCreatorOrAdminCanAddFilesAndOnlyToUploadSources() throws Exception {
        AsUser.admin();
        DataSource adminSource = sources.create("AdminSrc " + System.nanoTime(), SourceKind.UPLOAD, "files");
        if (allowedDomains.all().stream().noneMatch(d -> d.getDomain().equals("example.com"))) {
            allowedDomains.add("example.com");
        }
        DataSource site = sources.create("Site " + System.nanoTime(), SourceKind.WEBSITE, "https://example.com");
        AsUser.owner();
        assertThatThrownBy(() -> ingestion.addFile(adminSource.getId(), "a.txt", bytes("x y z"))).isInstanceOf(AccessDeniedException.class);
        AsUser.admin2();
        ingestion.addFile(adminSource.getId(), "a.txt", bytes("x y z"));
        assertThatThrownBy(() -> ingestion.addFile(site.getId(), "a.txt", bytes("x y z"))).hasMessage("error.upload.kind");
    }
}
