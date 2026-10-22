package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.source.ingest.TextExtractor;
import com.agenticpilot.source.ingest.UploadProperties;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SpreadsheetExtractionTest {

    private final TextExtractor extractor = new TextExtractor(new UploadProperties(1024 * 1024, 50_000, 3, 20));

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void csvRowsBecomeHeaderValueLines() {
        String text = extractor.extract("prices.csv", bytes("Plan,Price,Note\nBasic,9,\"cheap, small\"\nPro,29,\"said \"\"best\"\"\"\n")).text();
        assertThat(text).isEqualTo("Columns: Plan, Price, Note\nPlan: Basic; Price: 9; Note: cheap, small\nPlan: Pro; Price: 29; Note: said \"best\"");
    }

    @Test
    void csvWithSemicolonsBomAndQuotedNewlines() {
        String text = extractor.extract("a.csv", bytes("﻿Name;City\r\nAnna;\"Ber\nlin\"\r\n")).text();
        assertThat(text).contains("Name: Anna; City: Ber lin");
    }

    @Test
    void csvWithTooManyRowsIsRefused() {
        String many = "a,b\n" + "1,2\n".repeat(30);
        assertThatThrownBy(() -> extractor.extract("big.csv", bytes(many))).hasMessage("upload.error.rows");
    }

    @Test
    void unterminatedQuoteIsUnreadable() {
        assertThatThrownBy(() -> extractor.extract("bad.csv", bytes("a,b\n\"open,1\n"))).hasMessage("upload.error.unreadable");
    }

    private static byte[] xlsx(String sheet1, String shared) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "xl/workbook.xml", "<workbook xmlns=\"x\" xmlns:r=\"r\"><sheets><sheet name=\"Prices\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            put(zip, "xl/sharedStrings.xml", shared);
            put(zip, "xl/worksheets/sheet1.xml", sheet1);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes(content));
        zip.closeEntry();
    }

    @Test
    void xlsxReadsSharedStringsNumbersAndSkippedCells() throws Exception {
        String shared = "<sst><si><t>Plan</t></si><si><t>Price</t></si><si><t>Basic</t></si><si><t>Note</t></si><si><t>Small &amp; cheap</t></si></sst>";
        String sheet = "<worksheet><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>9.5</v></c><c r=\"D2\" t=\"s\"><v>4</v></c></row>"
                + "</sheetData></worksheet>";
        String text = extractor.extract("prices.xlsx", xlsx(sheet, shared)).text();
        assertThat(text).isEqualTo("Sheet: Prices\nColumns: Plan, Price, Note\nPlan: Basic; Price: 9.5; Note: Small & cheap");
    }

    @Test
    void xlsxWithExternalEntityIsNotExpanded() throws Exception {
        String shared = "<!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><sst><si><t>&x;</t></si></sst>";
        String sheet = "<worksheet><sheetData><row><c r=\"A1\" t=\"s\"><v>0</v></c></row><row><c r=\"A2\"><v>1</v></c></row></sheetData></worksheet>";
        try {
            assertThat(extractor.extract("e.xlsx", xlsx(sheet, shared)).text()).doesNotContain("root:");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).startsWith("upload.error");
        }
    }

    @Test
    void renamedOrBrokenXlsxIsRefused() {
        assertThatThrownBy(() -> extractor.extract("a.xlsx", bytes("just text"))).hasMessage("upload.error.mismatch");
        assertThatThrownBy(() -> extractor.extract("a.xlsx", new byte[] {'P', 'K', 3, 4, 0, 0})).hasMessage("upload.error.unreadable");
    }
}
