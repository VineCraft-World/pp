package world.vinecraft.pp;
import java.util.List;

public record Challenge(String id, String prompt, List<String> commands) {
}
