package mozhi.parseltongue.arena;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GameErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<GameController.Envelope<Void>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new GameController.Envelope<>(
                exception.getStatusCode().value() * 100, exception.getReason(), null));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<GameController.Envelope<Void>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new GameController.Envelope<>(40000,
                "请求参数无效，请检查输入内容和可选范围", null));
    }
}
