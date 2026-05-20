package de.intranda.goobi.plugins.createfullpdf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.Step;
import org.jfree.util.Log;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import de.sub.goobi.config.ConfigurationHelper;

public class CreateFullPdfPluginTest {

    private static final String TEST_IMAGE_DIR =
            Paths.get("src/test/resources/testprocess/images/testprocess_media").toAbsolutePath().toString() + "/";
    private static final String TEST_METS_FILE =
            Paths.get("src/test/resources/testprocess/meta.xml").toAbsolutePath().toString();

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Configure NIOFileUtils.imageNameFilter to accept any filename (not just \d{8} prefix)
        String configFile = Paths.get("src/test/resources/config/goobi_config.properties")
                .toAbsolutePath()
                .toString();
        ConfigurationHelper.configFileName = configFile;
        ConfigurationHelper.resetConfigurationFile();
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testNoGrouping() throws Exception {
        File exportDir = folder.newFolder("export");

        CreateFullPdfPlugin plugin = new CreateFullPdfPlugin(
                buildConfig(exportDir.getAbsolutePath(), ""), mockHelper(), mockMessages());
        plugin.initialize(mockStep(exportDir), "");

        assertTrue(plugin.execute());

        File fullPdfDir = new File(exportDir, "testProcess_fullpdf");
        assertTrue("fullpdf directory should exist", fullPdfDir.exists());

        File[] pdfs = fullPdfDir.listFiles((d, n) -> n.endsWith(".pdf"));
        assertEquals("without grouping exactly one full PDF should be created", 1, pdfs.length);
        assertEquals("testProcess.pdf", pdfs[0].getName());
    }

    @Test
    public void testWithGrouping() throws Exception {
        File exportDir = folder.newFolder("export");

        CreateFullPdfPlugin plugin = new CreateFullPdfPlugin(
                buildConfig(exportDir.getAbsolutePath(), "^(.+)_\\d{4}_"), mockHelper(), mockMessages());
        plugin.initialize(mockStep(exportDir), "");

        assertTrue(plugin.execute());

        File fullPdfDir = new File(exportDir, "testProcess_fullpdf");
        assertTrue("fullpdf directory should exist", fullPdfDir.exists());

        File[] pdfs = fullPdfDir.listFiles((d, n) -> n.endsWith(".pdf"));
        assertEquals("with grouping one PDF per group should be created", 4, pdfs.length);

        Set<String> names = Arrays.stream(pdfs).map(File::getName).collect(Collectors.toSet());
        Set<String> expected = new HashSet<>(Arrays.asList(
                "Mus_NL_19_I_Ccc_11_3.pdf",
                "Mus_NL_19_I_Ccc_11_4.pdf",
                "Mus_NL_77_Gac_10_1.pdf",
                "Mus_NL_77_Gac_10_2.pdf"));
        assertEquals(expected, names);
    }

    // --- helpers ---

    private ConfigurationHelper mockHelper() {
        ConfigurationHelper helper = Mockito.mock(ConfigurationHelper.class);
        Mockito.when(helper.getProcessOcrPdfDirectoryName()).thenReturn("{processtitle}_pdf");
        return helper;
    }

    private MessageHelper mockMessages() {
        return (id, type, message) -> Log.info("%s message for process %s: %s".formatted(type, id, message));
    }

    private Step mockStep(File exportDir) throws Exception {
        Process process = mockProcess(exportDir);
        Step step = Mockito.mock(Step.class);
        Mockito.when(step.getTitel()).thenReturn("testStep");
        Mockito.when(step.getProzess()).thenReturn(process);
        return step;
    }

    private Process mockProcess(File exportDir) throws Exception {
        Project project = Mockito.mock(Project.class);
        Mockito.when(project.getTitel()).thenReturn("testProject");
        Mockito.when(project.getId()).thenReturn(1);
        Mockito.when(project.getProjectIdentifier()).thenReturn("");

        Process process = Mockito.mock(Process.class);
        Mockito.when(process.getTitel()).thenReturn("testProcess");
        Mockito.when(process.getId()).thenReturn(1);
        Mockito.when(process.getProjekt()).thenReturn(project);
        Mockito.when(process.getOcrDirectory()).thenReturn(exportDir.getAbsolutePath() + "/");
        Mockito.when(process.getOcrAltoDirectory()).thenReturn(folder.newFolder().getAbsolutePath() + "/");
        Mockito.when(process.getMetadataFilePath()).thenReturn(TEST_METS_FILE);
        Mockito.when(process.getImagesTifDirectory(false)).thenReturn(TEST_IMAGE_DIR);
        Mockito.when(process.getImagesOrigDirectory(false)).thenReturn(folder.newFolder().getAbsolutePath() + "/");
        return process;
    }

    private SubnodeConfiguration buildConfig(String exportPath, String groupingRegex) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<config_plugin><config>"
                + "<project>*</project><step>*</step>"
                + "<pagePdf enabled=\"true\"/>"
                + "<fullPdf enabled=\"true\" pdfConfigVariant=\"default\"/>"
                + "<exportPath>" + exportPath + "</exportPath>"
                + (StringUtils.isBlank(groupingRegex) ? ""
                        : "<groupingRegex>" + groupingRegex + "</groupingRegex>")
                + "</config></config_plugin>";
        try {
            XMLConfiguration xmlConfig = new XMLConfiguration();
            xmlConfig.load(new StringReader(xml));
            xmlConfig.setExpressionEngine(new XPathExpressionEngine());
            return xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}
