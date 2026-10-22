package com.agenticpilot.source.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads CSV and Excel (.xlsx) files as text, read only. The first row is the header; every further row becomes one line
 * "Header: value; Header: value", so keyword search finds a row by any of its values. Formulas are not run: the stored
 * result is read. Dates in Excel files appear as their stored number. Reading is bounded in rows, columns and characters.
 */
final class SheetReader {

    static final int MAX_COLUMNS = 100;

    private final UploadProperties limits;

    SheetReader(UploadProperties limits) {
        this.limits = limits;
    }

    // ----- CSV -----

    String csv(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        char delimiter = delimiter(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (rows.size() > limits.maxRows()) {
                    throw new UploadRejectedException("upload.error.rows", limits.maxRows());
                }
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        if (quoted) {
            throw new UploadRejectedException("upload.error.unreadable");
        }
        return render(null, rows);
    }

    private static char delimiter(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        int commas = firstLine.length() - firstLine.replace(",", "").length();
        int semicolons = firstLine.length() - firstLine.replace(";", "").length();
        int tabs = firstLine.length() - firstLine.replace("\t", "").length();
        if (tabs > commas && tabs > semicolons) {
            return '\t';
        }
        return semicolons > commas ? ';' : ',';
    }

    // ----- XLSX -----

    String xlsx(byte[] data) {
        if (data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            throw new UploadRejectedException("upload.error.mismatch");
        }
        long budget = Math.max(limits.maxBytes(), 1) * 4;
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            long used = 0;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("xl/workbook.xml") || name.equals("xl/sharedStrings.xml") || name.equals("xl/_rels/workbook.xml.rels")
                        || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) {
                    byte[] bytes = zip.readNBytes((int) Math.min(budget - used + 1, Integer.MAX_VALUE));
                    used += bytes.length;
                    if (used > budget) {
                        throw new UploadRejectedException("upload.error.tooMuchText");
                    }
                    parts.put(name, bytes);
                }
            }
        } catch (IOException e) {
            throw new UploadRejectedException("upload.error.unreadable");
        }
        if (!parts.containsKey("xl/workbook.xml")) {
            throw new UploadRejectedException("upload.error.unreadable");
        }
        List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
        Map<String, String> targets = relationships(parts.get("xl/_rels/workbook.xml.rels"));
        StringBuilder out = new StringBuilder();
        int[] remaining = {limits.maxRows()};
        for (String[] sheet : sheets(parts.get("xl/workbook.xml"))) {
            String target = targets.get(sheet[1]);
            byte[] xml = parts.get(target == null ? "" : "xl/" + target.replaceFirst("^/?(xl/)?", ""));
            if (xml == null) {
                continue;
            }
            String rendered = render(sheet[0], sheetRows(xml, shared, remaining));
            if (!rendered.isBlank()) {
                out.append(rendered).append("\n\n");
            }
            if (out.length() > limits.maxChars()) {
                throw new UploadRejectedException("upload.error.tooMuchText");
            }
        }
        return out.toString();
    }

    /** Pairs of sheet name and relationship id, in workbook order. */
    private List<String[]> sheets(byte[] xml) {
        List<String[]> result = new ArrayList<>();
        read(xml, r -> {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())) {
                    String id = null;
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        if ("id".equals(r.getAttributeLocalName(i))) {
                            id = r.getAttributeValue(i);
                        }
                    }
                    result.add(new String[] {r.getAttributeValue(null, "name"), id});
                }
            }
            return null;
        });
        return result;
    }

    private Map<String, String> relationships(byte[] xml) {
        Map<String, String> map = new HashMap<>();
        if (xml == null) {
            return map;
        }
        read(xml, r -> {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())) {
                    map.put(r.getAttributeValue(null, "Id"), r.getAttributeValue(null, "Target"));
                }
            }
            return null;
        });
        return map;
    }

    private List<String> sharedStrings(byte[] xml) {
        List<String> strings = new ArrayList<>();
        read(xml, r -> {
            StringBuilder current = null;
            boolean inText = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("si".equals(r.getLocalName())) {
                        current = new StringBuilder();
                    }
                    inText = "t".equals(r.getLocalName());
                } else if (event == XMLStreamConstants.CHARACTERS && inText && current != null) {
                    current.append(r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    inText = false;
                    if ("si".equals(r.getLocalName()) && current != null) {
                        strings.add(current.toString());
                        current = null;
                    }
                }
                if (strings.size() > limits.maxRows() * MAX_COLUMNS) {
                    throw new UploadRejectedException("upload.error.tooMuchText");
                }
            }
            return null;
        });
        return strings;
    }

    private List<List<String>> sheetRows(byte[] xml, List<String> shared, int[] remaining) {
        List<List<String>> rows = new ArrayList<>();
        read(xml, r -> {
            List<String> row = null;
            String type = null;
            int column = 0;
            StringBuilder value = null;
            String element = null;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    element = r.getLocalName();
                    switch (element) {
                        case "row" -> row = new ArrayList<>();
                        case "c" -> {
                            type = r.getAttributeValue(null, "t");
                            column = columnIndex(r.getAttributeValue(null, "r"), row == null ? 0 : row.size());
                            value = new StringBuilder();
                            while (row != null && row.size() < Math.min(column, MAX_COLUMNS)) {
                                row.add("");
                            }
                        }
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && value != null && ("v".equals(element) || "t".equals(element))) {
                    value.append(r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String end = r.getLocalName();
                    if ("c".equals(end) && row != null && value != null && column < MAX_COLUMNS) {
                        row.add(cellText(type, value.toString(), shared));
                        value = null;
                    } else if ("row".equals(end) && row != null) {
                        rows.add(row);
                        row = null;
                        if (--remaining[0] < 0) {
                            throw new UploadRejectedException("upload.error.rows", limits.maxRows());
                        }
                    }
                    element = null;
                }
            }
            return null;
        });
        return rows;
    }

    private static String cellText(String type, String raw, List<String> shared) {
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw.strip());
                return index >= 0 && index < shared.size() ? shared.get(index) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if ("b".equals(type)) {
            return "1".equals(raw.strip()) ? "TRUE" : "FALSE";
        }
        return raw;
    }

    /** "C5" gives 2. Without a usable reference the cell follows the previous one. */
    private static int columnIndex(String ref, int fallback) {
        if (ref == null) {
            return fallback;
        }
        int index = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            index = index * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : Math.min(index - 1, 10_000);
    }

    private <T> void read(byte[] xml, XmlAction<T> action) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = new ByteArrayInputStream(xml)) {
            action.run(factory.createXMLStreamReader(in));
        } catch (XMLStreamException | IOException e) {
            throw new UploadRejectedException("upload.error.unreadable");
        }
    }

    @FunctionalInterface
    private interface XmlAction<T> {
        T run(XMLStreamReader reader) throws XMLStreamException;
    }

    // ----- shared output -----

    private String render(String sheetName, List<List<String>> rows) {
        List<List<String>> data = rows.stream().filter(r -> r.stream().anyMatch(c -> !c.isBlank())).toList();
        if (data.isEmpty()) {
            return "";
        }
        List<String> header = data.get(0).stream().map(String::strip).toList();
        StringBuilder out = new StringBuilder();
        if (sheetName != null) {
            out.append("Sheet: ").append(sheetName.strip()).append('\n');
        }
        out.append("Columns: ").append(String.join(", ", header.stream().filter(h -> !h.isEmpty()).toList())).append('\n');
        for (int r = 1; r < data.size(); r++) {
            List<String> row = data.get(r);
            List<String> parts = new ArrayList<>();
            for (int c = 0; c < Math.min(row.size(), MAX_COLUMNS); c++) {
                String v = row.get(c).strip().replaceAll("\\s+", " ");
                if (v.isEmpty()) {
                    continue;
                }
                String h = c < header.size() && !header.get(c).isEmpty() ? header.get(c) : "Column " + (c + 1);
                parts.add(h + ": " + v);
            }
            if (!parts.isEmpty()) {
                out.append(String.join("; ", parts)).append('\n');
            }
            if (out.length() > limits.maxChars()) {
                throw new UploadRejectedException("upload.error.tooMuchText");
            }
        }
        return out.toString().stripTrailing();
    }
}
