package de.intranda.goobi.plugins.createfullpdf;

import org.goobi.production.enums.LogType;

public interface MessageHelper {

    public void addMessageToProcessJournal(Integer processId, LogType logType, String message);

}
