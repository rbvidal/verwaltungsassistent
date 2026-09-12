package verwaltungsassistent.web.pdfplayground;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock data container for the Chromium/Playwright decision-template PoC.
 * Holds all dynamic fields that are injected into the HTML templates.
 */
public final class DecisionTemplateData {

    private String variant;
    private String caseTitle;
    private String processingStatus;
    private String confidence;
    private List<String> documents = new ArrayList<>();

    private String processorName;
    private String processorRole;
    private String processorRoom;
    private String processorPhone;
    private String processorEmail;

    private String vorgangsnummer;
    private String aktenzeichen;
    private String fallart;

    private String recommendation;
    private List<String> kernfeststellungen = new ArrayList<>();
    private List<String> weitereErkenntnisse = new ArrayList<>();
    private List<String> offenePunkte = new ArrayList<>();
    private List<String> naechsteSchritte = new ArrayList<>();

    private String footerPage;
    private String exportDate;

    public String getVariant() {
        return variant;
    }

    public void setVariant(String variant) {
        this.variant = variant;
    }

    public String getCaseTitle() {
        return caseTitle;
    }

    public void setCaseTitle(String caseTitle) {
        this.caseTitle = caseTitle;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public List<String> getDocuments() {
        return documents;
    }

    public void setDocuments(List<String> documents) {
        this.documents = documents;
    }

    public String getProcessorName() {
        return processorName;
    }

    public void setProcessorName(String processorName) {
        this.processorName = processorName;
    }

    public String getProcessorRole() {
        return processorRole;
    }

    public void setProcessorRole(String processorRole) {
        this.processorRole = processorRole;
    }

    public String getProcessorRoom() {
        return processorRoom;
    }

    public void setProcessorRoom(String processorRoom) {
        this.processorRoom = processorRoom;
    }

    public String getProcessorPhone() {
        return processorPhone;
    }

    public void setProcessorPhone(String processorPhone) {
        this.processorPhone = processorPhone;
    }

    public String getProcessorEmail() {
        return processorEmail;
    }

    public void setProcessorEmail(String processorEmail) {
        this.processorEmail = processorEmail;
    }

    public String getVorgangsnummer() {
        return vorgangsnummer;
    }

    public void setVorgangsnummer(String vorgangsnummer) {
        this.vorgangsnummer = vorgangsnummer;
    }

    public String getAktenzeichen() {
        return aktenzeichen;
    }

    public void setAktenzeichen(String aktenzeichen) {
        this.aktenzeichen = aktenzeichen;
    }

    public String getFallart() {
        return fallart;
    }

    public void setFallart(String fallart) {
        this.fallart = fallart;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public List<String> getKernfeststellungen() {
        return kernfeststellungen;
    }

    public void setKernfeststellungen(List<String> kernfeststellungen) {
        this.kernfeststellungen = kernfeststellungen;
    }

    public List<String> getWeitereErkenntnisse() {
        return weitereErkenntnisse;
    }

    public void setWeitereErkenntnisse(List<String> weitereErkenntnisse) {
        this.weitereErkenntnisse = weitereErkenntnisse;
    }

    public List<String> getOffenePunkte() {
        return offenePunkte;
    }

    public void setOffenePunkte(List<String> offenePunkte) {
        this.offenePunkte = offenePunkte;
    }

    public List<String> getNaechsteSchritte() {
        return naechsteSchritte;
    }

    public void setNaechsteSchritte(List<String> naechsteSchritte) {
        this.naechsteSchritte = naechsteSchritte;
    }

    public String getFooterPage() {
        return footerPage;
    }

    public void setFooterPage(String footerPage) {
        this.footerPage = footerPage;
    }

    public String getExportDate() {
        return exportDate;
    }

    public void setExportDate(String exportDate) {
        this.exportDate = exportDate;
    }
}
