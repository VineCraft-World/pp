package world.vinecraft.pp;

import java.util.List;

public record DetectedChallenge(String challenge_id, String player, String trigger, List<String> commands) {

}
