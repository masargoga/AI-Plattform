package com.agenticpilot.source.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads text out of uploads: PDF, Word (.docx), plain text and Markdown. The file type is decided by the extension and
 * confirmed by the file's first bytes, so a renamed file is refused. Extraction is bounded in pages and characters.
 */
public final class TextExtractor {

    public static final Set<String> TYPES = Set.of("pdf", "docx", "txt", "md");

    private final UploadProperties limits;

    public TextExtractor(UploadProperties limits) {
        this.limits = limits;
    }

    public ExtractedText extract(String filename, byte[] data) {
        if (data == null || data.length == 0) {
            throw new UploadRejectedException("upload.error.empty");
        }
        if (data.length > limits.maxBytes()) {
            throw new UploadRejectedException("upload.error.tooLarge", limits.maxBytes() / (1024 * 1024));
        }
        String name = cleanName(filename);
        String ext = extension(name);
        if (!TYPES.contains(ext)) {
            throw new UploadRejectedException("upload.error.type");
        }
        String text = switch (ext) {
            case "pdf" -> pdf(data);
            case "docx" -> docx(data);
            default -> plain(data);
        };
        text = normalise(text);
        if (text.isBlank()) {
            throw new UploadRejectedException("upload.error.noText");
        }
        return new ExtractedText(name, text);
    }

    /** The file name without any directory part; never empty. */
    static String cleanName(String filename) {
        String n = filename == null ? "" : filename.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).strip();
        n = n.replaceAll("[\\p{Cntrl}]", "");
        return n.isEmpty() ? "document" : n;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String plain(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new UploadRejectedException("upload.error.encoding");
        }
    }

    private String pdf(byte[] data) {
        if (data.length < 5 || data[0] != '%' || data[1] != 'P' || data[2] != 'D' || data[3] != 'F') {
            throw new UploadRejectedException("upload.error.mismatch");
        }
        try (PDDocument doc = Loader.loadPDF(data)) {
            if (doc.getNumberOfPages() > limits.maxPdfPages()) {
                throw new UploadRejectedException("upload.error.pages", limits.maxPdfPages());
            }
            return new PDFTextStripper().getText(doc);
        } catch (InvalidPasswordException e) {
            throw new UploadRejectedException("upload.error.encrypted");
        } catch (IOException | RuntimeException e) {
            if (e instanceof UploadRejectedException rejected) {
                throw rejected;
            }
            throw new UploadRejectedException("upload.error.unreadable");
        }
    }

    private String docx(byte[] data) {
        if (data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            throw new UploadRejectedException("upload.error.mismatch");
        }
        long budget = Math.max(limits.maxBytes(), 1) * 2;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return documentXml(new BoundedInputStream(zip, budget));
                }
            }
        } catch (IOException e) {
            throw new UploadRejectedException("upload.error.unreadable");
        }
        throw new UploadRejectedException("upload.error.unreadable");
    }

    private String documentXml(InputStream in) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        StringBuilder out = new StringBuilder();
        try {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            boolean inText = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    inText = "t".equals(local);
                    if ("tab".equals(local)) {
                        out.append('\t');
                    } else if ("br".equals(local)) {
                        out.append('\n');
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inText) {
                    out.append(r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    inText = false;
                    if ("p".equals(r.getLocalName())) {
                        out.append('\n');
                    }
                }
                if (out.length() > limits.maxChars()) {
                    throw new UploadRejectedException("upload.error.tooMuchText");
                }
            }
        } catch (XMLStreamException e) {
            throw new UploadRejectedException("upload.error.unreadable");
        }
        return out.toString();
    }

    private String normalise(String text) {
        if (text.length() > limits.maxChars()) {
            throw new UploadRejectedException("upload.error.tooMuchText");
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
                .replaceAll("[ \\t\\f]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** Stops reading after a fixed number of bytes, so a small zip cannot expand without limit. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left-- <= 0) {
                throw new IOException("limit");
            }
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                throw new IOException("limit");
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }
    }
}
