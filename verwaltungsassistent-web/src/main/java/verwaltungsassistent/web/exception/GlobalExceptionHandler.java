package verwaltungsassistent.web.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public String handleResponseStatus(ResponseStatusException ex, Model model,
                                        HttpServletResponse response) {
        log.debug("Response status: {} — {}", ex.getStatusCode(), ex.getReason());
        model.addAttribute("errorMessage", ex.getReason());
        response.setStatus(ex.getStatusCode().value());
        if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
            return "error/404";
        }
        if (ex.getStatusCode() == HttpStatus.FORBIDDEN) {
            return "error/403";
        }
        if (ex.getStatusCode() == HttpStatus.LOCKED) {
            return "error/423";
        }
        return "error/500";
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNoHandler(NoHandlerFoundException ex, Model model) {
        log.debug("No handler: {}", ex.getRequestURL());
        return "error/404";
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException ex, Model model) {
        log.debug("Resource not found: {}", ex.getMessage());
        return "error/404";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public String handleValidation(MethodArgumentNotValidException ex, Model model) {
        model.addAttribute("errors", ex.getBindingResult().getAllErrors());
        return "error/422";
    }

    /**
     * An oversized upload is a client-side problem, not a server error: the
     * upload form shows a clear German message instead of a generic 500.
     * Handling the exception also prevents the partially-consumed multipart
     * request from poisoning the pooled connection (which previously made
     * the NEXT upload on the same connection fail with the same exception).
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public String handleMaxUploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected: file exceeds the configured multipart limit");
        return "redirect:/documents/upload?error=maxsize";
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNoResource(NoResourceFoundException ex, HttpServletRequest request,
                                   Model model) {
        log.warn("Resource not found — exception: {} | message: {} | request: {} {}",
                ex.getClass().getSimpleName(),
                ex.getMessage() != null ? ex.getMessage() : "(no message)",
                request.getMethod(), request.getRequestURI());
        return "error/404";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleUnexpected(Exception ex, HttpServletRequest request, Model model) {
        // Concise diagnostic line; the full stack trace stays available at DEBUG
        // so genuine production failures remain debuggable without console spam.
        log.error("Unexpected error — exception: {} | message: {} | request: {} {}",
                ex.getClass().getSimpleName(),
                ex.getMessage() != null ? ex.getMessage() : "(no message)",
                request.getMethod(), request.getRequestURI());
        if (log.isDebugEnabled()) {
            log.debug("Stack trace for unexpected error", ex);
        }
        return "error/500";
    }
}
