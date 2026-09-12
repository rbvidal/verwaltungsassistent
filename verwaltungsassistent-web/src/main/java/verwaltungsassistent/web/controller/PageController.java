package verwaltungsassistent.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class PageController {

    // /cases is now handled by CaseController — the real Case List implementation
    // /documents is now handled by DocumentController — upload, indexing, list
    // /knowledge is now handled by KnowledgeController — search, hybrid retrieval
    // /assistant is now handled by AssistantController — the real Verwaltungsassistent assistant

    // /decisions is now handled by DecisionsController — the real review surface

    // /audit is now handled by AuditController — the real read-only audit view

    // /corpus is now handled by CorpusController — the real corpus administration surface

    // /admin is now handled by AdminController — the real administration surface
    // /profile is now handled by ProfileController — the real profile page
}
