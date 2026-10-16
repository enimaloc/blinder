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
    void quotesWhenNeeded() {
        assertThat(CsvWriter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.cell("a\nb")).isEqualTo("\"a\nb\"");
        assertThat(CsvWriter.cell(" x")).isEqualTo("\" x\"");
        assertThat(CsvWriter.cell("plain")).isEqualTo("plain");
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
