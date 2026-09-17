package de.danoeh.antennapod.desktop.xml;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import org.xmlpull.v1.XmlSerializer;

public class SimpleXmlSerializer implements XmlSerializer {
    private Writer out;
    private int depth;
    private boolean indent;
    private boolean openTag;

    @Override
    public void setFeature(String name, boolean state) {
        if (name != null && name.endsWith("indent-output")) {
            indent = state;
        }
    }

    @Override
    public boolean getFeature(String name) {
        return name != null && name.endsWith("indent-output") && indent;
    }

    @Override
    public void setProperty(String name, Object value) {
    }

    @Override
    public Object getProperty(String name) {
        return null;
    }

    @Override
    public void setOutput(OutputStream os, String encoding) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setOutput(Writer writer) {
        this.out = writer;
    }

    @Override
    public void startDocument(String encoding, Boolean standalone) throws IOException {
        out.write("<?xml version=\"1.0\" encoding=\"" + (encoding != null ? encoding : "UTF-8") + "\"?>");
        if (indent) {
            out.write("\n");
        }
    }

    @Override
    public void endDocument() throws IOException {
        if (indent) {
            out.write("\n");
        }
        out.flush();
    }

    @Override
    public void setPrefix(String prefix, String namespace) {
    }

    @Override
    public String getPrefix(String namespace, boolean generatePrefix) {
        return "";
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public String getNamespace() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public XmlSerializer startTag(String namespace, String name) throws IOException {
        closeOpenTag();
        if (indent) {
            out.write("\n");
            writeIndent();
        }
        out.write("<" + name);
        openTag = true;
        depth++;
        return this;
    }

    @Override
    public XmlSerializer attribute(String namespace, String name, String value) throws IOException {
        out.write(" " + name + "=\"" + escape(value, true) + "\"");
        return this;
    }

    @Override
    public XmlSerializer endTag(String namespace, String name) throws IOException {
        depth--;
        if (openTag) {
            out.write("/>");
            openTag = false;
        } else {
            if (indent) {
                out.write("\n");
                writeIndent();
            }
            out.write("</" + name + ">");
        }
        return this;
    }

    @Override
    public XmlSerializer text(String text) throws IOException {
        closeOpenTag();
        out.write(escape(text, false));
        return this;
    }

    @Override
    public XmlSerializer text(char[] buf, int start, int len) throws IOException {
        return text(new String(buf, start, len));
    }

    @Override
    public void cdsect(String text) throws IOException {
        closeOpenTag();
        out.write("<![CDATA[" + text + "]]>");
    }

    @Override
    public void entityRef(String text) throws IOException {
        closeOpenTag();
        out.write("&" + text + ";");
    }

    @Override
    public void processingInstruction(String text) throws IOException {
        closeOpenTag();
        out.write("<?" + text + "?>");
    }

    @Override
    public void comment(String text) throws IOException {
        closeOpenTag();
        out.write("<!--" + text + "-->");
    }

    @Override
    public void docdecl(String text) throws IOException {
        out.write("<!DOCTYPE " + text + ">");
    }

    @Override
    public void ignorableWhitespace(String text) throws IOException {
        out.write(text);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    private void closeOpenTag() throws IOException {
        if (openTag) {
            out.write(">");
            openTag = false;
        }
    }

    private void writeIndent() throws IOException {
        for (int i = 0; i < depth; i++) {
            out.write("  ");
        }
    }

    private static String escape(String value, boolean attribute) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append(attribute ? "&quot;" : "\"");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
