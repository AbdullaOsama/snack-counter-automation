package snackcounter.fakes;

import snackcounter.ports.ChatGateway;

import java.util.ArrayList;
import java.util.List;

/** Simulated chat tool: records every message so tests can assert on them, optionally echoes to stdout. */
public final class FakeChat implements ChatGateway {

    public record Message(String to, boolean channel, String text) {
        @Override
        public String toString() {
            return (channel ? to : "@" + to) + ": " + text;
        }
    }

    private final List<Message> messages = new ArrayList<>();
    private final boolean echo;

    public FakeChat() {
        this(false);
    }

    public FakeChat(boolean echo) {
        this.echo = echo;
    }

    @Override
    public synchronized void sendDirect(String employeeId, String text) {
        record(new Message(employeeId, false, text));
    }

    @Override
    public synchronized void postToChannel(String channel, String text) {
        record(new Message(channel, true, text));
    }

    private void record(Message m) {
        messages.add(m);
        if (echo) {
            System.out.println("  [chat] " + m);
        }
    }

    public synchronized List<Message> all() {
        return List.copyOf(messages);
    }

    public synchronized List<String> directTo(String employeeId) {
        return messages.stream().filter(m -> !m.channel() && m.to().equals(employeeId)).map(Message::text).toList();
    }

    public synchronized List<String> channel(String channel) {
        return messages.stream().filter(m -> m.channel() && m.to().equals(channel)).map(Message::text).toList();
    }

    public synchronized String lastDirectTo(String employeeId) {
        List<String> direct = directTo(employeeId);
        return direct.isEmpty() ? null : direct.get(direct.size() - 1);
    }
}
