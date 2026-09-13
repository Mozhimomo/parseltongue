package mozhi.parseltongue.llm;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class LlmErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<GenerationController.Envelope<Void>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new GenerationController.Envelope<>(
                exception.getStatusCode().value() * 100, exception.getReason(), null));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<GenerationController.Envelope<Void>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new GenerationController.Envelope<>(40000, "模型请求参数无效", null));
    }
}
