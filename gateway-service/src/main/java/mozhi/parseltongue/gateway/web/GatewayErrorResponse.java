package mozhi.parseltongue.gateway.web;

public record GatewayErrorResponse(
        int code,
        String message,
        Object data
) {
    public static GatewayErrorResponse error(int code, String message) {
        return new GatewayErrorResponse(code, message, null);
    }
}
