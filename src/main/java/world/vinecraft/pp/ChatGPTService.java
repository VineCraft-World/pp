package world.vinecraft.pp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import world.bentobox.bentobox.BentoBox;

public class ChatGPTService {
    private static final String URL = "https://api.openai.com/v1/chat/completions";
    private final String apiKey;
    private final PpAddon addon;
    private final String mainPrompt;

    public ChatGPTService(PpAddon addon, String apiKey) { 
        this.apiKey = apiKey; 
        this.addon = addon;
        this.mainPrompt = addon.getConfig().getString("prompt",
                "You are a Minecraft server assistant who is reacting to prayers submitted by players. Respond only in JSON format. The JSON must contain an array called 'triggered_challenges'. Each element in the array must be an object with the following fields: 'id' (the challenge ID), 'player' (the affected player name), 'reward' (the enum name of a Bukkit Material that may be given as a reward), 'qty' the quantity of reward to give.");
    }

    /**
     * Sends chat + challenge definitions to GPT,
     * expects JSON: { "<challengeId>": ["PlayerA","PlayerB"], ... }
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<Winner>> evaluateChallenges(Map<String, Object> payload) {
        try {
            // Construct the JSON payload
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "gpt-4.1");

            JSONArray messages = new JSONArray();

            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", mainPrompt);
            messages.add(systemMessage);

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            String toSend = new JSONObject(payload).toString();
            userMessage.put("content", toSend);
            messages.add(userMessage);

            requestBody.put("messages", messages);

            // Sanitize the API key
            String sanitizedApiKey = apiKey != null ? apiKey.replace("“", "").replace("”", "").trim() : "";

            // Open connection
            HttpURLConnection connection = (HttpURLConnection) new URI(URL).toURL().openConnection();
            connection.setRequestMethod("POST");

            // Use the sanitized API key
            connection.setRequestProperty("Authorization", "Bearer " + sanitizedApiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Send the request
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.toString().getBytes());
                os.flush();
            }

            // Check for HTTP response code
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                addon.logError("Error: Unauthorized (401). Please check your API key.");
                return Collections.emptyMap();
            } else if (responseCode != HttpURLConnection.HTTP_OK) {
                addon.logError("Error: Received HTTP response code " + responseCode);
                return Collections.emptyMap();
            }

            // Read the response
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            // Parse the response
            JSONParser parser = new JSONParser();
            JSONObject jsonResponse = (JSONObject) parser.parse(response.toString());
            JSONObject choice = (JSONObject) ((JSONArray) jsonResponse.get("choices")).get(0);
            JSONObject message = (JSONObject) choice.get("message");
            Object content = message.get("content");

            // Ensure content is a String
            if (!(content instanceof String)) {
                addon.logError("Unexpected content type: " + content.getClass().getName());
                return Collections.emptyMap();
            }

            // Parse the content string into JSON
            Object parsedContent = parser.parse((String) content);

            // Check if the parsed content is a JSONObject or JSONArray
            if (parsedContent instanceof JSONObject) {
                JSONObject jsonObject = (JSONObject) parsedContent;

                // Handle JSONObject case
                if (!jsonObject.containsKey("triggered_challenges")) {
                    return Collections.emptyMap();
                }

                JSONArray triggeredChallenges = (JSONArray) jsonObject.get("triggered_challenges");
                return processTriggeredChallenges(triggeredChallenges);
            } else if (parsedContent instanceof JSONArray) {
                JSONArray jsonArray = (JSONArray) parsedContent;

                // Handle JSONArray case (if applicable)
                return processTriggeredChallenges(jsonArray);
            } else {
                addon.logError("Unexpected parsed content type: " + parsedContent.getClass().getName());
                return Collections.emptyMap();
            }
        } catch (Exception e) {
            addon.logError("Exception occurred while parsing response: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyMap();
        }
    }
    
    public record Winner(String playerName, String reward, Long qty) {
    }

    private Map<String, List<Winner>> processTriggeredChallenges(JSONArray triggeredChallenges) {
        BentoBox.getInstance().logDebug(triggeredChallenges);
        Map<String, List<Winner>> result = new HashMap<>();

        for (Object challengeObj : triggeredChallenges) {
            JSONObject challenge = (JSONObject) challengeObj;
            String challengeId = (String) Objects.requireNonNullElse(challenge.get("id"), "");
            String player = (String) Objects.requireNonNullElse(challenge.get("player"), "");
            String reward = (String) Objects.requireNonNullElse(challenge.get("reward"), "");
            Long qty = (Long) Objects.requireNonNullElse(challenge.get("qty"), 0);
            Winner winner = new Winner(player, reward, qty);

            // Log challenge details for debugging
            addon.log("Triggered Challenge ID: " + challengeId + ", Affected Player: " + player + " Reward: " + reward
                    + " qty: " + qty);

            // Add challenge ID and player to the result map
            result.compute(challengeId, (k, v) -> v == null ? new ArrayList<>() : v).add(winner);
        }

        return result;
    }
}
