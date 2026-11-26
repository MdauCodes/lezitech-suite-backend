package com.lezitech.suite.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A spreadsheet runs a cell that starts like a formula, so those cells are made harmless. */
class OversightCsvTest {

    @Test
    void cellsThatStartLikeAFormulaOrWithATabOrReturnAreMadeHarmless() {
        assertThat(Oversight.csv("=SUM(A1)")).isEqualTo("\"'=SUM(A1)\"");
        assertThat(Oversight.csv("+1+1")).isEqualTo("\"'+1+1\"");
        assertThat(Oversight.csv("@cmd")).isEqualTo("\"'@cmd\"");
        assertThat(Oversight.csv("\t=1+1")).isEqualTo("\"'\t=1+1\"");
        assertThat(Oversight.csv("\r=1+1")).startsWith("\"'\r");
        // Plain numbers and words are left alone, and quotes are doubled.
        assertThat(Oversight.csv("-5,000", "Kamau \"Duka\"")).isEqualTo("\"-5,000\",\"Kamau \"\"Duka\"\"\"");
    }
}
