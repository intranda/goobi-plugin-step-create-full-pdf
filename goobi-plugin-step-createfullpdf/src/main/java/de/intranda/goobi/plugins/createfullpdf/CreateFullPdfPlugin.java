package de.intranda.goobi.plugins.createfullpdf;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.goobi.beans.LogEntry;
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
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.unigoettingen.sub.commons.contentlib.exceptions.ContentLibException;
import de.unigoettingen.sub.commons.contentlib.servlet.controller.GetPdfAction;
import de.unigoettingen.sub.commons.contentlib.servlet.model.ContentServerConfiguration;
import de.unigoettingen.sub.commons.contentlib.servlet.model.MetsPdfRequest;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j
public class CreateFullPdfPlugin implements IStepPluginVersion2 {

    private static String TITLE = "intranda_step_createfullpdf";
    private Step step;

    @Override
    public void initialize(Step step, String returnPath) {
        // TODO Auto-generated method stub
        this.step = step;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public String cancel() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String finish() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Step getStep() {
        // TODO Auto-generated method stub
        return step;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        // TODO Auto-generated method stub
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PluginType getType() {
        // TODO Auto-generated method stub
        return PluginType.Step;
    }

    @Override
    public String getTitle() {
        // TODO Auto-generated method stub
        return TITLE;
    }

    @Override
    public PluginReturnValue run() {
        XMLConfiguration config = ConfigPlugins.getPluginConfig(TITLE);
        Process p = step.getProzess();
        try {
            Path metsP = Paths.get(p.getMetadataFilePath());

            Path pdfDir = Paths.get(p.getOcrPdfDirectory());
            Path fullPdfDir = Paths.get(p.getOcrDirectory()).resolve(p.getTitel() + "_fullpdf");
            Path fullPdfFile = fullPdfDir.resolve(p.getTitel() + ".pdf");
            if (!Files.exists(pdfDir)) {
                Files.createDirectories(pdfDir);
            }
            if (config.getBoolean("writeSinglePdfsFirst", true)) {
                boolean ok = createPdfsSinglePageFirst(config, p, pdfDir, fullPdfFile);
                if (!ok) {
                    return PluginReturnValue.ERROR;
                }
            } else {
                boolean ok = createPdfsFullPdfFirst(config, p, metsP, pdfDir, fullPdfDir, fullPdfFile);
                if (!ok) {
                    return PluginReturnValue.ERROR;
                }
            }
        } catch (URISyntaxException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            LogEntry entry = LogEntry.build(p.getId())
                    .withContent("PdfCreation: error while creating PDF file - for full details see the log file")
                    .withType(LogType.ERROR)
                    .withCreationDate(new Date());
            ProcessManager.saveLogEntry(entry);
            return PluginReturnValue.ERROR;
        } catch (PDFWriteException | PDFReadException e) {
            log.error(e);
            LogEntry entry = LogEntry.build(p.getId())
                    .withContent("PdfCreation: error while splitting PDF file - for full details see the log file")
                    .withType(LogType.ERROR)
                    .withCreationDate(new Date());
            ProcessManager.saveLogEntry(entry);
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }

    private boolean createPdfsFullPdfFirst(XMLConfiguration config, Process p, Path metsP, Path pdfDir, Path fullPdfDir,
            Path fullPdfFile)
            throws URISyntaxException, SwapException, DAOException, IOException, InterruptedException, PDFWriteException, PDFReadException {
        if (!Files.exists(fullPdfDir)) {
            Files.createDirectories(fullPdfDir);
        }
        ContentServerConfiguration csConfig = ContentServerConfiguration.getInstance();
        Map<String, String> parameters = new HashMap<>();
        MetsPdfRequest req = new MetsPdfRequest(0, metsP.toUri(), null, true, parameters);

        req.setAltoSource(Paths.get(p.getOcrAltoDirectory()).toUri());

        if (config.getBoolean("useMasterImages")) {
            req.setImageSource(Paths.get(p.getImagesOrigDirectory(false)).toUri());
        } else {
            req.setImageSource(Paths.get(p.getImagesTifDirectory(false)).toUri());
        }
        try (OutputStream os =
                Files.newOutputStream(fullPdfFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE)) {
            new GetMetsPdfAction().writePdf(req, csConfig, os);

        } catch (ContentLibException e) {
            log.error(e);
            LogEntry entry = LogEntry.build(p.getId())
                    .withContent("PdfCreation: error while creating PDF file - for full details see the log file")
                    .withType(LogType.ERROR)
                    .withCreationDate(new Date());
            ProcessManager.saveLogEntry(entry);
            return false;
        }
        // now split pdf
        splitPdf(pdfDir, fullPdfFile, p.getOcrAltoDirectory());
        if (config.getBoolean("deleteFullPdf", false)) {
            FileUtils.deleteQuietly(fullPdfDir.toFile());
        }
        return true;
    }

    private boolean createPdfsSinglePageFirst(XMLConfiguration config, Process p, Path pdfDir, Path fullPdfFile)
            throws SwapException, DAOException, IOException, InterruptedException, MalformedURLException, FileNotFoundException {
        Path altoDir = Paths.get(p.getOcrAltoDirectory());
        Path sourceDir = null;
        if (config.getBoolean("useMasterImages")) {
            sourceDir = Paths.get(p.getImagesOrigDirectory(false));
        } else {
            sourceDir = Paths.get(p.getImagesTifDirectory(false));
        }
        List<File> pdfFiles = new ArrayList<File>();
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(sourceDir, NIOFileUtils.imageNameFilter)) {
            for (Path imageFile : dirStream) {
                String imageFilename = imageFile.getFileName().toString();
                int lastDotIdx = imageFilename.lastIndexOf('.');
                String pdfFilename = imageFilename.substring(0, lastDotIdx) + ".pdf";
                String altoFilename = imageFilename.substring(0, lastDotIdx) + ".xml";
                Path pdfPath = pdfDir.resolve(pdfFilename);
                try (OutputStream os = Files.newOutputStream(pdfPath)) {
                    try {
                        Map<String, String> singlePdfParameters = new HashMap<>();
                        singlePdfParameters.put("images", imageFile.toUri().toString());
                        Path altoPath = altoDir.resolve(altoFilename);
                        if (Files.exists(altoPath)) {
                            singlePdfParameters.put("altos", altoPath.toUri().toString());
                        }
                        //                                singlePdfParameters.put("imageSource", imageFile.toUri().toString());
                        new GetPdfAction().writePdf(singlePdfParameters, os);
                        pdfFiles.add(pdfPath.toFile());
                    } catch (ContentLibException e) {
                        log.error(e);
                        LogEntry entry = LogEntry.build(p.getId())
                                .withContent("PdfCreation: error while creating PDF file - for full details see the log file")
                                .withType(LogType.ERROR)
                                .withCreationDate(new Date());
                        ProcessManager.saveLogEntry(entry);
                        return false;
                    }
                }
            }
        }
        if (!config.getBoolean("deleteFullPdf", false)) {
            if (!Files.exists(fullPdfFile.getParent())) {
                Files.createDirectories(fullPdfFile.getParent());
            }
            PDFMergerUtility pdfMerger = new PDFMergerUtility();
            Collections.sort(pdfFiles);
            for (File pdfFile : pdfFiles) {
                pdfMerger.addSource(pdfFile);
            }
            try (OutputStream os =
                    Files.newOutputStream(fullPdfFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE)) {
                pdfMerger.setDestinationStream(os);
                pdfMerger.mergeDocuments(MemoryUsageSetting.setupMixed(524288000l));
            }
        }
        return true;
    }

    public static void splitPdf(Path pdfDir, Path fullPdfFile, String altoDir)
            throws PDFWriteException, PDFReadException, SwapException, DAOException, IOException, InterruptedException {
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
        // TODO Auto-generated method stub
        return 0;
    }

}
