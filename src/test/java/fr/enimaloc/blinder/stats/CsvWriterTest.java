package fr.enimaloc.blinder.stats;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void neutralisesFormulas() {
        assertThat(CsvWriter.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvWriter.cell("-5")).isEqualTo("'-5");
        assertThat(CsvWriter.cell("+1")).isEqualTo("'+1");
        assertThat(CsvWriter.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvWriter.cell("\tx")).isEqualTo("'\tx");
    }

    @Test
    void neutralisesFormulasHiddenBehindWhitespaceControlsOrFullwidth() {
        assertThat(CsvWriter.cell("  =1+1")).isEqualTo("'  =1+1");
        assertThat(CsvWriter.cell("\n=cmd")).isEqualTo("\"'\n=cmd\"");
        assertThat(CsvWriter.cell("\u0000=x")).isEqualTo("'\u0000=x");
        assertThat(CsvWriter.cell("\uFF1D1+1")).isEqualTo("'\uFF1D1+1");
        assertThat(CsvWriter.cell("\uFF20SUM(A1)")).isEqualTo("'\uFF20SUM(A1)");
        assertThat(CsvWriter.cell("\uFF0B1")).isEqualTo("'\uFF0B1");
        assertThat(CsvWriter.cell("\uFF0D1")).isEqualTo("'\uFF0D1");
        assertThat(CsvWriter.cell("-5")).isEqualTo("'-5");
        assertThat(CsvWriter.cell("a-b")).isEqualTo("a-b");
        assertThat(CsvWriter.cell(" plain ")).isEqualTo("\" plain \"");
    }

    @Test
    void quotesWhenNeeded() {
        assertThat(CsvWriter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.cell("a\nb")).isEqualTo("\"a\nb\"");
        assertThat(CsvWriter.cell(" x")).isEqualTo("\" x\"");
        assertThat(CsvWriter.cell("plain")).isEqualTo("plain");
    }

    @Test
    void neutralisesFormulasHiddenBehindInvisibleFormatCharacters() {
        assertThat(CsvWriter.cell("\u200B=1+1")).isEqualTo("'\u200B=1+1");
        assertThat(CsvWriter.cell("\uFEFF@SUM(A1)")).isEqualTo("'\uFEFF@SUM(A1)");
        assertThat(CsvWriter.cell("\u2060-5")).isEqualTo("'\u2060-5");
        assertThat(CsvWriter.cell("\u200Bplain")).isEqualTo("\u200Bplain");
    }

    @Test
    void nullIsEmpty() {
        assertThat(CsvWriter.cell(null)).isEmpty();
    }

    @Test
    void rowUsesCrlf() throws IOException {
        StringWriter out = new StringWriter();
        new CsvWriter(out).row("a", null, "=x");
        assertThat(out).hasToString("a,,'=x\r\n");
    }
}
