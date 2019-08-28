package de.intranda.goobi.plugins.createfullpdf;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.XMLConfiguration;
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
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.unigoettingen.sub.commons.contentlib.exceptions.ContentLibException;
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
        Map<String, String> parameters = new HashMap<>();
        try {
            Path metsP = Paths.get(p.getMetadataFilePath());
            MetsPdfRequest req = new MetsPdfRequest(0, metsP.toUri(), null, true, parameters);

            req.setAltoSource(Paths.get(p.getOcrAltoDirectory()).toUri());

            if (config.getBoolean("useMasterImages")) {
                req.setImageSource(Paths.get(p.getImagesOrigDirectory(false)).toUri());
            } else {
                req.setImageSource(Paths.get(p.getImagesTifDirectory(false)).toUri());
            }

            Path pdfDir = Paths.get(p.getOcrPdfDirectory());
            Path fullPdfDir = Paths.get(p.getOcrDirectory()).resolve(p.getTitel() + "_fullpdf");
            Path fullPdfFile = fullPdfDir.resolve("full.pdf");
            if (!Files.exists(fullPdfDir)) {
                Files.createDirectories(fullPdfDir);
            }
            if (!Files.exists(pdfDir)) {
                Files.createDirectories(pdfDir);
            }
            ContentServerConfiguration csConfig = ContentServerConfiguration.getInstance();
            try (OutputStream os =
                    Files.newOutputStream(fullPdfFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                new GetMetsPdfAction().writePdf(req, csConfig, os);
            } catch (ContentLibException e) {
                log.error(e);
                LogEntry entry = LogEntry.build(p.getId())
                        .withContent("PdfCreation: error while creating PDF file - for full details see the log file")
                        .withType(LogType.ERROR)
                        .withCreationDate(new Date());
                ProcessManager.saveLogEntry(entry);
                return PluginReturnValue.ERROR;
            }
            // now split pdf
            List<File> createdPdfs = PDFConverter.writeSinglePagePdfs(fullPdfFile.toFile(), pdfDir.toFile());

            for (int i = createdPdfs.size(); i >= 0; i--) {
                File pdfFile = createdPdfs.get(i);
                Path newName = pdfFile.toPath().resolveSibling(String.format("%08d.pdf", i + 1));
                Files.move(pdfFile.toPath(), newName, StandardCopyOption.REPLACE_EXISTING);
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

    @Override
    public int getInterfaceVersion() {
        // TODO Auto-generated method stub
        return 0;
    }

}
