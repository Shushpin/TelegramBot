package lnu.study.service.enums;

public enum ServiceCommand {
    HELP("/help"),
    REGISTRATION("/registration"),
    CANCEL("/cancel"),
    START("/start"),
    RESEND_EMAIL("/resend_email"),
    CONVERT_FILE("/convert_file"),
    GENERATE_LINK("/generate_link"), // Крапку з комою тут прибираємо, якщо додаємо нову команду після
    CREATE_ARCHIVE("/create_archive"); // Нова команда, тепер вона остання, тому тут крапка з комою

    private final String value;

    ServiceCommand(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static ServiceCommand fromValue(String v) {
        // Додамо перевірку на null для більшої надійності, якщо її ще немає
        if (v == null) {
            return null;
        }
        for (ServiceCommand c : ServiceCommand.values()) {
            if (c.value.equals(v)) {
                return c;
            }
        }
        return null;
    }
}