function fillTemplate(data) {
    const setText = (selector, text) => {
        const el = document.querySelector(selector);
        if (el) el.textContent = text == null ? '' : text;
    };

    const setHtml = (selector, html) => {
        const el = document.querySelector(selector);
        if (el) el.innerHTML = html == null ? '' : html;
    };

    const renderList = (items, ordered) => {
        if (!items || items.length === 0) return '';
        const tag = ordered ? 'ol' : 'ul';
        const liStyle = 'margin: 0 0 2px 0; padding-left: 14px; font-size: 8.5px; color: #0f172a; line-height: 1.35;';
        const lis = items.map(i => `<li style="${liStyle}">${escapeHtml(String(i))}</li>`).join('');
        return `<${tag} style="margin: 0; padding-left: 14px;">${lis}</${tag}>`;
    };

    const escapeHtml = (text) => {
        if (text == null) return '';
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    };

    const renderParagraphs = (text) => {
        if (!text) return '';
        const pStyle = 'margin: 0 0 4px 0; font-size: 8.5px; color: #0f172a; line-height: 1.4;';
        return text.split('\n')
            .map(l => l.trim())
            .filter(l => l.length > 0)
            .map(l => `<p style="${pStyle}">${escapeHtml(l)}</p>`)
            .join('');
    };

    const renderRecommendation = () => {
        const labelStyle = 'font-weight: 700; color: #08182b; margin-right: 4px;';
        const parts = [];
        if (data.kurzantwort) {
            parts.push(`<p style="margin: 0 0 4px 0; font-size: 8.5px; color: #0f172a; line-height: 1.4;"><span style="${labelStyle}">Kurzantwort:</span>${escapeHtml(data.kurzantwort)}</p>`);
        }
        if (data.entscheidung) {
            parts.push(`<p style="margin: 0 0 4px 0; font-size: 8.5px; color: #0f172a; line-height: 1.4;"><span style="${labelStyle}">Entscheidung:</span>${escapeHtml(data.entscheidung)}</p>`);
        }
        if (data.rechtsgrundlage) {
            parts.push(`<div style="margin: 0 0 4px 0; font-size: 8.5px; color: #0f172a; line-height: 1.4;"><span style="${labelStyle}">Rechtsgrundlage:</span>${renderParagraphs(data.rechtsgrundlage)}</div>`);
        }
        if (parts.length === 0 && data.recommendation) {
            parts.push(`<p style="margin: 0; font-size: 8.5px; color: #0f172a; line-height: 1.4;">${escapeHtml(data.recommendation)}</p>`);
        }
        return parts.join('');
    };

    // Case title
    setText('.doc-reserved-space', data.caseTitle);

    // Status values
    setText('#processingStatus', data.processingStatus);
    setText('#confidence', data.confidence);

    // Documents
    const docBox = document.querySelector('.status-box-vertical');
    if (docBox && data.documents && data.documents.length > 0) {
        const itemStyle = 'font-size: 7.5px; color: #0f172a; margin: 1px 0 0 0; line-height: 1.3; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;';
        const items = data.documents.map(d => `<div style="${itemStyle}">• ${d}</div>`).join('');
        const docIconSvg = '<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/></svg>';
        const headerRow = '<div style="display: flex; align-items: center; gap: 6px; width: 100%;">'
            + '<div class="status-icon-box icon-box-blue">' + docIconSvg + '</div>'
            + '<div class="status-label" style="font-size: 7.5px; line-height: 1.15;">Dokumente im Vorgang</div>'
            + '</div>';
        docBox.innerHTML = headerRow + items;
    }

    // Foto-/Bilddokumente: kleine Vorschaubilder (~2 cm, Seitenverhältnis
    // erhalten) mit Dateiname/Titel statt nur der Dateinamen-Liste.
    const imagesArea = document.querySelector('.pdf-images-area');
    if (imagesArea && data.documentImages && data.documentImages.length > 0) {
        const items = data.documentImages.map(function (img) {
            const title = escapeHtml(img.title || '');
            const src = img.dataUrl || '';
            return '<div class="pdf-images-item">'
                + '<img src="' + src + '" alt="' + title + '"/>'
                + (title ? '<div class="pdf-images-caption">' + title + '</div>' : '')
                + '</div>';
        }).join('');
        imagesArea.innerHTML = '<div class="pdf-images-heading">Foto- / Bilddokumente im Vorgang</div>' + items;
    }

    // Processor box
    setText('.processor-name-fill', data.processorName);
    setText('.processor-sub', data.processorRole);
    setText('.processor-sub-secondary', data.processorRoom);
    const contactRows = document.querySelectorAll('.processor-contact-row span');
    if (contactRows.length >= 2) {
        contactRows[0].textContent = data.processorPhone || '';
        contactRows[1].textContent = data.processorEmail || '';
    }

    // Vorgangsnummer / Aktenzeichen / Fallart
    const dashedLines = document.querySelectorAll('.field-dashed-line');
    if (dashedLines.length >= 3) {
        dashedLines[0].textContent = data.vorgangsnummer || '';
        dashedLines[0].style.cssText += 'font-size: 9px; font-weight: 700; color: #0f172a; padding-top: 1px;';
        dashedLines[1].textContent = data.aktenzeichen || '';
        dashedLines[1].style.cssText += 'font-size: 9px; font-weight: 700; color: #0f172a; padding-top: 1px;';
        dashedLines[2].textContent = data.fallart || '';
        dashedLines[2].style.cssText += 'font-size: 9px; font-weight: 700; color: #0f172a; padding-top: 1px;';
    }

    // Recommendation
    const recDynamic = document.querySelector('.rec-dynamic-space');
    if (recDynamic) {
        setHtml('.rec-dynamic-space', renderRecommendation());
    } else {
        const recEmpty = document.querySelector('.rec-empty-space');
        if (recEmpty) {
            setHtml('.rec-empty-space', renderRecommendation());
        } else {
            // Ocker variant uses static text blocks
            setText('.rec-v1-text', data.recommendation || '');
        }
    }

    // Writing areas (in DOM order: Kernfeststellungen, Weitere Erkenntnisse, Offene Punkte, Nächste Schritte)
    const writingAreas = document.querySelectorAll('.writing-area');
    if (writingAreas.length >= 4) {
        writingAreas[0].innerHTML = renderList(data.kernfeststellungen, false);
        let weitere = renderList(data.weitereErkenntnisse, false);
        // Konfidenz-Erläuterung NACH den tatsächlichen Erkenntnissen: die PDF
        // erklärt eine reduzierte Gesamtkonfidenz, statt eine unerklärte Zahl
        // stehen zu lassen (gleiche Semantik wie die UI-Erklärbox).
        if (data.confidenceHint) {
            weitere += `<p style="margin: 4px 0 0 0; font-size: 7.5px; color: #b45309; line-height: 1.4;">${escapeHtml(data.confidenceHint)}</p>`;
        }
        writingAreas[1].innerHTML = weitere;
        writingAreas[2].innerHTML = renderList(data.offenePunkte, true);
        writingAreas[3].innerHTML = renderList(data.naechsteSchritte, true);
    }

    // Footer
    setText('.page-marker', data.footerPage || '');
    const exportSpans = document.querySelectorAll('.footer-right span span');
    if (exportSpans.length > 0) {
        exportSpans[exportSpans.length - 1].textContent = data.exportDate || '';
    }
}
