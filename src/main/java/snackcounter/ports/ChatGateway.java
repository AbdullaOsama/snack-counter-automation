package snackcounter.ports;

/**
 * The company chat tool. In production this would be a bot (Slack/Teams/etc.) whose
 * buttons ("Accept", "Decline", "Upload receipt") call the RefillService commands.
 */
public interface ChatGateway {

    void sendDirect(String employeeId, String text);

    void postToChannel(String channel, String text);
}
