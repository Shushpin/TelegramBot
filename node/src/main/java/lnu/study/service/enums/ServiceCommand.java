package lnu.study.service.enums;

public enum ServiceCommand {
    HELP("/help"),
    REGISTRATION("/registration"),
    CANCEL("/cancel"),
    START("/start"),
    RESEND_EMAIL("/resend_email"),
    CONVERT_FILE("/convert_file"),
    GENERATE_LINK("/generate_link");;

    private final String value;

    ServiceCommand(String value) {
        this.value = value;
    }


    @Override
    public String toString() {
        return value;
    }


    public static ServiceCommand fromValue(String v) {
        for (ServiceCommand c : ServiceCommand.values()) {
            if (c.value.equals(v)) {
                return c;
            }
        }
        return null;
    }

}