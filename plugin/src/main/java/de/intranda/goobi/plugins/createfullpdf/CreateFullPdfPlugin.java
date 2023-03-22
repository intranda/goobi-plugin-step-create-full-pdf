package de.intranda.goobi.plugins.createfullpdf;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.presentation.contentServlet.controller.GetMetsPdfAction;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.intranda.digiverso.pdf.PDFConverter;
import de.intranda.digiverso.pdf.exception.PDFReadException;
import de.intranda.digiverso.pdf.exception.PDFWriteException;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.unigoettingen.sub.commons.contentlib.exceptions.ContentLibException;
import de.unigoettingen.sub.commons.contentlib.servlet.controller.GetPdfAction;
import de.unigoettingen.sub.commons.contentlib.servlet.model.ContentServerConfiguration;
import de.unigoettingen.sub.commons.contentlib.servlet.model.MetsPdfRequest;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j
public class CreateFullPdfPlugin implements IStepPluginVersion2 {

    private static final long serialVersionUID = -5076430372560062201L;

    private static final String TITLE = "intranda_step_createfullpdf";
    private static final String ERROR_CREATING_MESSAGE = "PdfCreation: error while creating PDF file - for full details see the log file";
    private static final String ERROR_SPLITING_MESSAGE = "PdfCreation: error while splitting PDF file - for full details see the log file";
    private Step step;

    private Path exportDirectory = null;

    private String processOcrPdfDirectoryName;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null; //NOSONAR
    }

    @Override
    public Step getStep() {
        return step;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String getTitle() {
        return TITLE;
    }

    @Override
    public PluginReturnValue run() {

        log.debug("================= STARTING CREATE-FULL-PDF PLUGIN =================");

        Process p = step.getProzess();
        SubnodeConfiguration config = getConfig(p);

        String imageFolder = config.getString("/imageFolder", "media");
        boolean pagePdf = config.getBoolean("/pagePdf/@enabled");
        boolean keepFullPdf = config.getBoolean("/fullPdf/@enabled");
        String pdfConfigVariant = config.getString("/fullPdf/@pdfConfigVariant", "default");

        String exportPath = config.getString("/exportPath", "");
        if (StringUtils.isNotBlank(exportPath)) {
            exportDirectory = Paths.get(exportPath);
        }

        processOcrPdfDirectoryName = VariableReplacer.simpleReplace(ConfigurationHelper.getInstance().getProcessOcrPdfDirectoryName(), p);
        log.debug("processOcrPdfDirectoryName = " + processOcrPdfDirectoryName);

        try {
            boolean ok = createPdfs(p, imageFolder, keepFullPdf, pagePdf, pdfConfigVariant);
            if (!ok) {
                return PluginReturnValue.ERROR;
            }

        } catch (URISyntaxException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            Helper.addMessageToProcessJournal(p.getId(), LogType.ERROR, ERROR_CREATING_MESSAGE, "");
            return PluginReturnValue.ERROR;
        } catch (PDFWriteException | PDFReadException e) {
            log.error(e);
            Helper.addMessageToProcessJournal(p.getId(), LogType.ERROR, ERROR_SPLITING_MESSAGE, "");
            return PluginReturnValue.ERROR;
        }

        return PluginReturnValue.FINISH;
    }

    private SubnodeConfiguration getConfig(Process p) {
        String projectName = p.getProjekt().getTitel();
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(TITLE);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration config = null;

        // order of configuration is:
        // 1.) project name and step name matches
        // 2.) step name matches and project is *
        // 3.) project name matches and step name is *
        // 4.) project name and step name are *
        try {
            config = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
        } catch (IllegalArgumentException e) {
            try {
                config = xmlConfig.configurationAt("//config[./project = '*'][./step = '" + step.getTitel() + "']");
            } catch (IllegalArgumentException e1) {
                try {
                    config = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
                } catch (IllegalArgumentException e2) {
                    config = xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
                }
            }
        }

        return config;
    }

    private boolean createPdfs(Process p, String imageFolder, boolean keepFullPdf, boolean pagePdf, String pdfConfigVariant)
            throws SwapException, DAOException, IOException, InterruptedException, URISyntaxException, PDFWriteException, PDFReadException {

        // use default ocr directory for export if not configured
        if (exportDirectory == null) {
            exportDirectory = Paths.get(p.getOcrDirectory());
        }

        log.debug("exportDirectory = " + exportDirectory);

        return pagePdf ? createPdfsSinglePageFirst(p, imageFolder, keepFullPdf, pdfConfigVariant)
                : createPdfsFullPageFirst(p, imageFolder, keepFullPdf, pdfConfigVariant);
    }

    private boolean createPdfsFullPageFirst(Process p, String folderName, boolean keepFullPdf, String pdfConfigVariant)
            throws URISyntaxException, SwapException, DAOException, IOException, InterruptedException, PDFWriteException, PDFReadException {

        boolean fullPageCreated = createFullPage(p, folderName, keepFullPdf, pdfConfigVariant);
        if (!fullPageCreated) {
            log.error("Errors happened while trying to create the full page.");
            return false;
        }

        List<File> singlePages = createSinglePages(p, folderName, pdfConfigVariant);
        if (singlePages == null) {
            log.error("Errors happened while trying to create single pages.");
            return false;
        }

        return true;
    }

    private boolean createPdfsSinglePageFirst(Process p, String folderName, boolean keepFullPdf, String pdfConfigVariant)
            throws SwapException, DAOException, IOException, InterruptedException {

        List<File> pdfFiles = createSinglePages(p, folderName, pdfConfigVariant);
        if (pdfFiles == null) {
            log.error("Errors happened while trying to create single pages.");
            return false;
        }

        // create a full page via merging if needed
        if (keepFullPdf) {
            try {
                mergePages(p, pdfFiles);

            } catch (IOException e) {
                log.error("IOException happened while trying to merge single pages into a full page.");
                return false;
            }
        }

        return true;
    }

    private boolean createFullPage(Process p, String folderName, boolean keepFullPdf, String pdfConfigVariant)
            throws URISyntaxException, SwapException, DAOException, IOException, InterruptedException, PDFWriteException, PDFReadException {

        Path metsP = Paths.get(p.getMetadataFilePath());

        Path pdfDir = exportDirectory.resolve(processOcrPdfDirectoryName);

        Path fullPdfDir = exportDirectory.resolve(p.getTitel() + "_fullpdf");
        Path fullPdfFile = fullPdfDir.resolve(p.getTitel() + ".pdf");

        if (!Files.exists(pdfDir)) {
            Files.createDirectories(pdfDir);
        }

        if (!Files.exists(fullPdfDir)) {
            Files.createDirectories(fullPdfDir);
        }

        ContentServerConfiguration csConfig = ContentServerConfiguration.getInstance();
        Map<String, String> parameters = new HashMap<>();
        // set the configured variant as value of the key "config" to use this config
        // if the variant is not configured in Goobi/src/main/resources/contentServerConfig.xml, then default settings will be used
        parameters.put("config", pdfConfigVariant);
        MetsPdfRequest req = new MetsPdfRequest(0, metsP.toUri(), null, true, parameters);

        log.debug("req.isWriteAsPdfa = " + req.isWriteAsPdfA());

        req.setAltoSource(Paths.get(p.getOcrAltoDirectory()).toUri());

        if ("master".equals(folderName)) {
            req.setImageSource(Paths.get(p.getImagesOrigDirectory(false)).toUri());
        } else { // media
            req.setImageSource(Paths.get(p.getImagesTifDirectory(false)).toUri());
        }

        try (OutputStream os =
                Files.newOutputStream(fullPdfFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            new GetMetsPdfAction().writePdf(req, csConfig, os);

        } catch (ContentLibException e) {
            log.error(e);
            Helper.addMessageToProcessJournal(p.getId(), LogType.ERROR, ERROR_CREATING_MESSAGE, "");
            return false;
        }

        return true;
    }


    private List<File> createSinglePages(Process p, String foldername, String pdfConfigVariant)
            throws SwapException, DAOException, IOException, InterruptedException {

        Path pdfDir = exportDirectory.resolve(processOcrPdfDirectoryName);

        if (!Files.exists(pdfDir)) {
            Files.createDirectories(pdfDir);
        }

        Path altoDir = Paths.get(p.getOcrAltoDirectory());
        Path sourceDir = null;

        if ("master".equals(foldername)) {
            sourceDir = Paths.get(p.getImagesOrigDirectory(false));
        } else { // use media otherwise
            sourceDir = Paths.get(p.getImagesTifDirectory(false));
        }

        List<File> pdfFiles = new ArrayList<>();

        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(sourceDir, NIOFileUtils.imageNameFilter)) {
            for (Path imageFile : dirStream) {
                String imageFilename = imageFile.getFileName().toString();
                int lastDotIdx = imageFilename.lastIndexOf('.');
                String pdfFilename = imageFilename.substring(0, lastDotIdx) + ".pdf";
                String altoFilename = imageFilename.substring(0, lastDotIdx) + ".xml";

                Path pdfPath = pdfDir.resolve(pdfFilename);
                try (OutputStream os = Files.newOutputStream(pdfPath)) {
                    Map<String, String> singlePdfParameters = new HashMap<>();

                    // set the configured variant as value of the key "config" to use this config
                    singlePdfParameters.put("config", pdfConfigVariant);

                    singlePdfParameters.put("images", imageFile.toUri().toString());

                    Path altoPath = altoDir.resolve(altoFilename);
                    if (Files.exists(altoPath)) {
                        singlePdfParameters.put("altos", altoPath.toUri().toString());
                    }

                    new GetPdfAction().writePdf(singlePdfParameters, os);
                    pdfFiles.add(pdfPath.toFile());

                } catch (ContentLibException e) {
                    log.error(e);
                    Helper.addMessageToProcessJournal(p.getId(), LogType.ERROR, ERROR_CREATING_MESSAGE, "");
                    return null;
                }
            }
        }

        return pdfFiles;
    }

    private void mergePages(Process p, List<File> pdfFiles) throws IOException {
        Path fullPdfDir = exportDirectory.resolve(p.getTitel() + "_fullpdf");
        Path fullPdfFile = fullPdfDir.resolve(p.getTitel() + ".pdf");

        if (!Files.exists(fullPdfDir)) {
            Files.createDirectories(fullPdfDir);
        }

        PDFMergerUtility pdfMerger = new PDFMergerUtility();
        Collections.sort(pdfFiles);

        for (File pdfFile : pdfFiles) {
            pdfMerger.addSource(pdfFile);
        }

        try (OutputStream os =
                Files.newOutputStream(fullPdfFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            pdfMerger.setDestinationStream(os);
            pdfMerger.mergeDocuments(MemoryUsageSetting.setupMixed(524288000l));
        }
    }

    public static void splitPdf(Path pdfDir, Path fullPdfFile, String altoDir) throws PDFWriteException, PDFReadException, IOException {
        List<File> createdPdfs = PDFConverter.writeSinglePagePdfs(fullPdfFile.toFile(), pdfDir.toFile());
        String[] altoNames = new File(altoDir).list();

        if (altoNames != null) {
            Arrays.sort(altoNames);
        }

        Collections.sort(createdPdfs);

        for (int i = createdPdfs.size() - 1; i >= 0; i--) {
            File pdfFile = createdPdfs.get(i);
            Path newPath = null;

            if (altoNames != null && altoNames.length == createdPdfs.size()) {
                String altoName = altoNames[i];
                String newName = altoName.substring(0, altoName.lastIndexOf('.')) + ".pdf";
                newPath = pdfFile.toPath().resolveSibling(newName);
            } else {
                newPath = pdfFile.toPath().resolveSibling(String.format("%08d.pdf", i + 1));
            }

            Files.move(pdfFile.toPath(), newPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

}
