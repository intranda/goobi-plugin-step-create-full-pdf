package de.intranda.goobi.plugins.createfullpdf;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
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

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.unigoettingen.sub.commons.contentlib.exceptions.ContentLibException;
import de.unigoettingen.sub.commons.contentlib.servlet.controller.GetPdfAction;
import de.unigoettingen.sub.commons.contentlib.servlet.model.ContentServerConfiguration;
import de.unigoettingen.sub.commons.contentlib.servlet.model.MetsPdfRequest;
import lombok.Getter;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j
public class CreateFullPdfPlugin implements IStepPluginVersion2 {

    private static final long serialVersionUID = -5076430372560062201L;

    @Getter
    private String title = "intranda_step_createfullpdf";
    private static final String ERROR_CREATING_MESSAGE = "PDF-Creation: Error while creating PDF file - for full details see the log file";
    private Step step;
    private Process process;

    private Path exportDirectory = null;
    private String processOcrPdfDirectoryName;

    private String imageFolder;
    private boolean singlePagePdf;
    private boolean fullPdf;
    private String fullPdfMode;
    private String pdfConfigVariant;

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
    public PluginReturnValue run() {

        log.debug("================= STARTING CREATE-FULL-PDF PLUGIN =================");

        process = step.getProzess();
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);

        imageFolder = config.getString("/imageFolder", "media");
        singlePagePdf = config.getBoolean("/singlePagePdf/@enabled", false);
        fullPdf = config.getBoolean("/fullPdf/@enabled", false);
        fullPdfMode = config.getString("/fullPdf/@mode", "mets").toLowerCase();
        pdfConfigVariant = config.getString("/fullPdf/@pdfConfigVariant", "default");

        try {

            // create export path
            String exportPath = config.getString("/exportPath", "");
            if (StringUtils.isNotBlank(exportPath)) {
                exportDirectory = Paths.get(exportPath);
            } else {
                // use default ocr directory for export if not configured
                exportDirectory = Paths.get(process.getOcrDirectory());
            }
            log.debug("exportDirectory = " + exportDirectory);

            // read potential source folder for source pdf files
            processOcrPdfDirectoryName = VariableReplacer.simpleReplace(ConfigurationHelper.getInstance().getProcessOcrPdfDirectoryName(), process);
            log.debug("processOcrPdfDirectoryName = " + processOcrPdfDirectoryName);

            // full PDF file from mets
            if (fullPdf && "mets".equals(fullPdfMode)) {
                // prepare the target pdf folder
                Path fullPdfFolder = exportDirectory.resolve(process.getTitel() + "_fullpdf");
                if (!Files.exists(fullPdfFolder)) {
                    Files.createDirectories(fullPdfFolder);
                }
                // do pdf generation
                boolean ok = createFullPdfFromMets(fullPdfFolder);
                if (!ok) {
                    log.error("An error happened while trying to create full PDF based on METS file.");
                    Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                            "An error happened while trying to create full PDF based on METS file.");
                    return PluginReturnValue.ERROR;
                }
            }

            // Single Page PDFs: if single PDF pages are requested OR a full PDF file from single pages
            List<File> singlePagePdfFiles = new ArrayList<File>();
            Path singlePagePdfFolder = exportDirectory.resolve(processOcrPdfDirectoryName);
            if (singlePagePdf || (fullPdf && !"mets".equals(fullPdfMode))) {
                // prepare the target pdf folder
                if (!Files.exists(singlePagePdfFolder)) {
                    Files.createDirectories(singlePagePdfFolder);
                }
                // do the pdf generation
                singlePagePdfFiles = createSinglePagePdfs(singlePagePdfFolder);
                if (singlePagePdfFiles == null) {
                    log.error("An error happened while trying to create single page PDF files.");
                    Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                            "An error happened while trying to create single page PDF files.");
                    return PluginReturnValue.ERROR;
                }
            }

            // full pdf file from single pages
            if (fullPdf && !"mets".equals(fullPdfMode)) {
                // prepare the target pdf folder
                Path fullPdfFolder = exportDirectory.resolve(process.getTitel() + "_fullpdf");
                if (!Files.exists(fullPdfFolder)) {
                    Files.createDirectories(fullPdfFolder);
                }
                // do pdf generation based on single page pdfs
                createFullPdfFromSinglePagePdfs(singlePagePdfFiles, fullPdfFolder);
            }

            // clean up single page PDF folder if just needed for full pdf generation
            if (!singlePagePdf && (fullPdf && !"mets".equals(fullPdfMode))) {
                StorageProvider.getInstance().deleteDir(singlePagePdfFolder);
            }

        } catch (URISyntaxException | IOException | SwapException | DAOException e) {
            log.error(e);
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, ERROR_CREATING_MESSAGE, "");
            return PluginReturnValue.ERROR;
        }

        return PluginReturnValue.FINISH;
    }

    /**
     * create single pages
     * 
     * @return the list of all generated single pages if successful, null otherwise
     * @throws SwapException
     * @throws DAOException
     * @throws IOException
     */
    private List<File> createSinglePagePdfs(Path pdfDir) throws IOException, SwapException, DAOException {

        Path altoDir = Paths.get(process.getOcrAltoDirectory());
        Path sourceDir = null;

        if ("master".equals(imageFolder)) {
            sourceDir = Paths.get(process.getImagesOrigDirectory(false));
        } else { // use media otherwise
            sourceDir = Paths.get(process.getImagesTifDirectory(false));
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
                    Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, ERROR_CREATING_MESSAGE, "");
                    return null;
                }
            }
        }

        return pdfFiles;
    }

    /**
     * merge a list of pages into a whole PDF file
     * 
     * @param pdfFiles the list of single pages that shall be merged
     * @throws IOException
     */
    private void createFullPdfFromSinglePagePdfs(List<File> pdfFiles, Path fullPdfDir) throws IOException {
        Path fullPdfFile = fullPdfDir.resolve(process.getTitel() + ".pdf");
        PDFMergerUtility pdfMerger = new PDFMergerUtility();
        Collections.sort(pdfFiles);

        for (File pdfFile : pdfFiles) {
            pdfMerger.addSource(pdfFile);
        }

        try (OutputStream os =
                Files.newOutputStream(fullPdfFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            pdfMerger.setDestinationStream(os);
            pdfMerger.mergeDocuments(null);
        }
    }

    /**
     * create a full PDF file from METS file
     * 
     * @return true if the full PDF file is successfully generated, false otherwise
     * @throws IOException
     * @throws SwapException
     * @throws URISyntaxException
     * @throws DAOException
     */
    private boolean createFullPdfFromMets(Path fullPdfDir)
            throws IOException, SwapException, URISyntaxException, DAOException {

        Path metsP = Paths.get(process.getMetadataFilePath());
        Path fullPdfFile = fullPdfDir.resolve(process.getTitel() + ".pdf");

        ContentServerConfiguration csConfig = ContentServerConfiguration.getInstance();
        Map<String, String> parameters = new HashMap<>();
        // set the configured variant as value of the key "config" to use this config
        // if the variant is not configured in Goobi/src/main/resources/contentServerConfig.xml, then default settings will be used
        parameters.put("config", pdfConfigVariant);
        MetsPdfRequest req = new MetsPdfRequest(0, metsP.toUri(), null, true, parameters);

        log.debug("req.isWriteAsPdfa = " + req.isWriteAsPdfA());

        req.setAltoSource(Paths.get(process.getOcrAltoDirectory()).toUri());

        if ("master".equals(imageFolder)) {
            req.setImageSource(Paths.get(process.getImagesOrigDirectory(false)).toUri());
        } else { // media
            req.setImageSource(Paths.get(process.getImagesTifDirectory(false)).toUri());
        }

        try (OutputStream os =
                Files.newOutputStream(fullPdfFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            new GetMetsPdfAction().writePdf(req, csConfig, os);

        } catch (ContentLibException e) {
            log.error(e);
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, ERROR_CREATING_MESSAGE, "");
            return false;
        }

        return true;
    }

    //    /**
    //     * was used to generate single pages via splitting the full PDF file, but it doesn't work correctly with PDF/A file
    //     *
    //     * @param pdfDir Path of the PDF directory
    //     * @param fullPdfFile Path to the full PDF file
    //     * @param altoDir Path of the ALTO directory as String
    //     * @throws PDFWriteException
    //     * @throws PDFReadException
    //     * @throws IOException
    //     */
    //    public static void splitPdf(Path pdfDir, Path fullPdfFile, String altoDir) throws PDFWriteException, PDFReadException, IOException {
    //        List<File> createdPdfs = PDFConverter.writeSinglePagePdfs(fullPdfFile.toFile(), pdfDir.toFile());
    //        String[] altoNames = new File(altoDir).list();
    //
    //        if (altoNames != null) {
    //            Arrays.sort(altoNames);
    //        }
    //
    //        Collections.sort(createdPdfs);
    //
    //        for (int i = createdPdfs.size() - 1; i >= 0; i--) {
    //            File pdfFile = createdPdfs.get(i);
    //            Path newPath = null;
    //
    //            if (altoNames != null && altoNames.length == createdPdfs.size()) {
    //                String altoName = altoNames[i];
    //                String newName = altoName.substring(0, altoName.lastIndexOf('.')) + ".pdf";
    //                newPath = pdfFile.toPath().resolveSibling(newName);
    //            } else {
    //                newPath = pdfFile.toPath().resolveSibling(String.format("%08d.pdf", i + 1));
    //            }
    //
    //            Files.move(pdfFile.toPath(), newPath, StandardCopyOption.REPLACE_EXISTING);
    //        }
    //    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

}
