package com.agenticpilot.source;

/** Kinds of data sources. Only UPLOAD and WEBSITE can be created for now; the rest appear in the picker as planned. */
public enum SourceKind {
    UPLOAD(true, "files"),
    WEBSITE(true, "pages"),
    FOLDER(false, "files"),
    WIKI(false, "pages"),
    DATABASE(false, "rows"),
    API(false, "records");

    private final boolean available;
    private final String unit;

    SourceKind(boolean available, String unit) {
        this.available = available;
        this.unit = unit;
    }

    public boolean isAvailable() { return available; }
    public String unit() { return unit; }
}
