package in.legalverify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainValidatorTest {
    private static final Path MOCKS = Path.of("..", "mocks", "map-outputs");
    private final ObjectMapper json = new ObjectMapper();

    private ChainValidator.Doc mock(String file, String tag) throws Exception {
        return new ChainValidator.Doc(file, tag, DocFacts.from(json.readTree(Files.readString(MOCKS.resolve(file)))));
    }

    @Test
    void sampleBundleRaisesTheExpectedFlags() throws Exception {
        // uploaded out of order on purpose
        var result = new ChainValidator().validate(List.of(
                mock("2024-ec.json", "EC"), mock("2019-gift-deed-survey-typo.json", "OTHER"), mock("2012-sale-deed.json", "SALE_DEED")));

        assertEquals(List.of("2345/2012", "4412/2019", "EC/GJW/2024/00917"), result.ordered().stream().map(d -> d.facts().docNo()).toList());
        List<String> codes = result.flags().stream().map(ChainValidator.Flag::code).toList();
        assertFalse(codes.contains("CHAIN_BREAK"), result.flags().toString());
        assertTrue(codes.contains("NAME_VARIANCE"), "LAKSHMI vs LAXMI");
        assertTrue(codes.contains("ENCUMBRANCE"));

        var survey = result.flags().stream().filter(f -> f.code().equals("PROPERTY_ID_MISMATCH")).toList();
        assertEquals(1, survey.size(), "only the survey number differs, plot no. is consistent");
        assertTrue(survey.get(0).message().contains("123/4A") && survey.get(0).message().contains("123/A4"));
        assertEquals(ChainValidator.CRITICAL, survey.get(0).severity());

        var missing = result.flags().stream().filter(f -> f.code().equals("MISSING_DOCUMENT")).map(ChainValidator.Flag::message).toList();
        assertEquals(2, missing.size(), missing.toString());
        assertTrue(missing.get(0).contains("1187/2005") && missing.get(1).contains("801/2021"));

        // 30 survey + 10 variance + 10 encumbrance + 2 x 10 missing
        assertEquals(70, result.riskScore());
        assertEquals("DEFECTIVE", result.status());

        var g = result.chainOfTitle();
        assertEquals(3, g.nodes().size(), "Venkata Rao -> Lakshmi -> Srinivas; EC is not a conveyance");
        assertEquals(List.of("transfer", "transfer"), g.edges().stream().map(ChainValidator.Edge::kind).toList());
        assertEquals("name spelt differently", g.edges().get(1).note());
    }

    @Test
    void unrelatedSellerBreaksTheChain() {
        var a = new DocFacts("Sale Deed", "10/2001", "2001-01-01", List.of("A. Ramu"), List.of("B. Krishna Murthy"),
                "Sy. No. 5", null, null, List.of(), List.of(), List.of());
        var b = new DocFacts("Sale Deed", "20/2010", "2010-01-01", List.of("C. Narayana"), List.of("D. Rani"),
                "Sy. No. 5", null, null, List.of(), List.of(), List.of());
        var r = new ChainValidator().validate(List.of(new ChainValidator.Doc("a", "SALE_DEED", a), new ChainValidator.Doc("b", "SALE_DEED", b)));
        assertEquals(List.of("CHAIN_BREAK"), r.flags().stream().map(ChainValidator.Flag::code).toList());
        assertEquals("CONDITIONAL", r.status());
        assertTrue(r.chainOfTitle().edges().stream().anyMatch(e -> e.kind().equals("break")));
    }

    @Test
    void nameMatching() {
        assertEquals(ChainValidator.Match.EXACT, ChainValidator.matchNames("K. Venkata Rao", "Sri KONDAPALLI VENKATA RAO, S/o Appa Rao"));
        assertEquals(ChainValidator.Match.VARIANT, ChainValidator.matchNames("P. Laxmi", "Smt. Pothula Lakshmi"));
        assertEquals(ChainValidator.Match.VARIANT, ChainValidator.matchNames("Srinivasa Rao", "Sreenivasa Rao"));
        assertEquals(ChainValidator.Match.NONE, ChainValidator.matchNames("P. Srinivas", "P. Lakshmi"));
        assertEquals(ChainValidator.Match.NONE, ChainValidator.matchNames("K. R.", "Kondapalli Rao"), "initials alone never match");
        assertEquals(List.of("2345/2012", "1187/2005"), ChainValidator.docNumbers("Doc 2345 / 2012 and Link Deed No.01187/2005"));
        assertEquals(List.of(), ChainValidator.docNumbers("EC/GJW/2024/00917"));
    }

    @Test
    void surveyNumberWithoutTheWordNo() {
        // live gpt-oss output wrote "Survey 123/4A"; the mismatch must still be caught
        var a = new DocFacts("Sale Deed", "1/2012", "2012-01-01", List.of("A Rao"), List.of("B Devi"), "Plot 17, Survey 123/4A", null, null, List.of(), List.of(), List.of());
        var b = new DocFacts("Gift Deed", "2/2019", "2019-01-01", List.of("B Devi"), List.of("C Rao"), "Sy.No: 123/A4, Plot No. 17, Patta No. 5521", null, null, List.of(), List.of(), List.of());
        var flags = new ChainValidator().validate(List.of(new ChainValidator.Doc("a", "SALE_DEED", a), new ChainValidator.Doc("b", "OTHER", b))).flags();
        assertEquals(List.of("PROPERTY_ID_MISMATCH"), flags.stream().map(ChainValidator.Flag::code).toList(), flags.toString());
        assertTrue(flags.get(0).message().startsWith("Survey No."), "plot 17 matches; only the survey number differs");
    }
}
