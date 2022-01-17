package extensions.outfitrandomizer;

import gearth.extensions.Extension;
import gearth.extensions.ExtensionInfo;
import gearth.extensions.parsers.HEntity;
import gearth.extensions.parsers.HEntityType;
import gearth.extensions.parsers.HGender;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.protocol.connection.HClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.XML;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@ExtensionInfo(
        Title = "OutfitRandomizer",
        Description = "Randomize outfit",
        Version = "0.2",
        Author = "WiredSpast"
)
public class OutfitRandomizer extends Extension {
    private JSONObject figureDataJson;
    private final List<Integer> ownedSellableIds = new ArrayList<>();
    private boolean isHCMember = false;
    private boolean checkedHCMemberShip = false;
    private HGender currentGender = HGender.Unisex;
    private Random random;

    private final HashMap<Integer, HEntity> users = new HashMap<>();
    private final HashMap<Integer, String> userFigures = new HashMap<>();
    private final HashMap<Integer, HGender> userGenders= new HashMap<>();

    private int selectedIndex = -1;

    public OutfitRandomizer(String[] args) {
        super(args);
    }

    public static void main(String[] args) {
        new OutfitRandomizer(args).run();
    }

    @Override
    protected void initExtension() {
        this.onConnect(this::onConnect);

        intercept(HMessage.Direction.TOSERVER, "Chat", this::onChatSend);
        intercept(HMessage.Direction.TOSERVER, "GetSelectedBadges", this::onGetSelectedBadges);

        intercept(HMessage.Direction.TOCLIENT, "FigureSetIds", this::onFigureSetIds);
        intercept(HMessage.Direction.TOCLIENT, "ScrSendUserInfo", this::onScrSendUserInfo);
        intercept(HMessage.Direction.TOCLIENT, "RoomReady", this::onRoomReady);
        intercept(HMessage.Direction.TOCLIENT, "Users", this::onUsers);
        intercept(HMessage.Direction.TOCLIENT, "UserChange", this::onUserChange);
        intercept(HMessage.Direction.TOCLIENT, "UserRemove", this::onUserRemove);

        random = new Random();
    }

    private void onChatSend(HMessage hMessage) {
        String msg = hMessage.getPacket().readString().toLowerCase();

        if(msg.startsWith(":outfit")) {
            hMessage.setBlocked(true);
            msg = msg.replace(":outfit", "").trim();

            if(msg.startsWith("copy")) {
                msg = msg.replace("copy", "").trim();

                if(!msg.isEmpty()) {
                    String finalMsg = msg;
                    selectedIndex = users.values().stream()
                            .filter(user -> user.getName().toLowerCase().equals(finalMsg))
                            .map(HEntity::getIndex)
                            .findFirst().orElse(-1);
                }

                if(users.containsKey(selectedIndex)) {
                    sendToServer(new HPacket("UpdateFigureData", HMessage.Direction.TOSERVER, userGenders.get(selectedIndex).toString(), userFigures.get(selectedIndex)));
                } else {
                    sendToServer(new HPacket("Whisper", HMessage.Direction.TOSERVER, "x Couldn't find user " + msg + " in room!", 0));
                }
            } else {
                if(msg.length() > 0) {
                    if("ufm".contains(msg.charAt(0) + "")) {
                        currentGender = HGender.fromString(msg.charAt(0) + "");
                    }
                }

                if(currentGender == HGender.Unisex) {
                    currentGender = random.nextInt(2) == 0 ? HGender.Female : HGender.Male;
                }

                String figureString = getRandomFigure(currentGender);
                sendToServer(new HPacket("UpdateFigureData", HMessage.Direction.TOSERVER, currentGender.toString(), figureString));
            }
        }
    }

    private void onGetSelectedBadges(HMessage hMessage) {
        int id = hMessage.getPacket().readInteger();
        selectedIndex = users.values().stream()
                .filter(hEntity -> hEntity.getId() == id)
                .map(HEntity::getIndex)
                .findFirst()
                .orElse(-1);
    }

    private void onFigureSetIds(HMessage hMessage) {
        HPacket packet = hMessage.getPacket();

        ownedSellableIds.clear();

        int n = packet.readInteger();
        for(int i = 0; i < n; i++) {
            ownedSellableIds.add(packet.readInteger());
        }
    }

    private void onScrSendUserInfo(HMessage hMessage) {
        HPacket packet = hMessage.getPacket();
        if(packet.readString().equals("club_habbo")) {
            isHCMember = true;
        }
    }

    private void onRoomReady(HMessage hMessage) {
        users.clear();
        userFigures.clear();
        userGenders.clear();

        if(!checkedHCMemberShip) {
            sendToServer(new HPacket("ScrGetUserInfo", HMessage.Direction.TOSERVER, "habbo_club"));
            checkedHCMemberShip = true;
        }
    }

    private void onUsers(HMessage hMessage) {
        Arrays.stream(HEntity.parse(hMessage.getPacket()))
                .filter(entity -> entity.getEntityType().equals(HEntityType.HABBO))
                .forEach(entity -> {
                    users.put(entity.getIndex(), entity);
                    userFigures.put(entity.getIndex(), entity.getFigureId());
                    userGenders.put(entity.getIndex(), entity.getGender());
                });
    }

    private void onUserChange(HMessage hMessage) {
        HPacket packet = hMessage.getPacket();
        int index = packet.readInteger();
        userFigures.replace(index, packet.readString());
        userGenders.replace(index, HGender.fromString(packet.readString()));
    }

    private void onUserRemove(HMessage hMessage) {
        int index = Integer.parseInt(hMessage.getPacket().readString());
        users.remove(index);
        userFigures.remove(index);
        userGenders.remove(index);
    }

    private void onConnect(String s, int i, String s1, String s2, HClient hClient) {
        isHCMember = false;
        fetchFigureDate(s);
    }

    private void fetchFigureDate(String gameServer) {
        String figureDataUrl = "https://www.habbo.com/gamedata/figuredata/1";

        switch(gameServer) {
            case "game-nl.habbo.com":
                figureDataUrl = "https://www.habbo.nl/gamedata/figuredata/1";
                break;
            case "game-us.habbo.com":
                figureDataUrl = "https://www.habbo.com/gamedata/figuredata/1";
                break;
            case "game-br.habbo.com":
                figureDataUrl = "https://www.habbo.com.br/gamedata/figuredata/1";
                break;
            case "game-tr.habbo.com":
                figureDataUrl = "https://www.habbo.com.tr/gamedata/figuredata/1";
                break;
            case "game-de.habbo.com":
                figureDataUrl = "https://www.habbo.de/gamedata/figuredata/1";
                break;
            case "game-fr.habbo.com":
                figureDataUrl = "https://www.habbo.fr/gamedata/figuredata/1";
                break;
            case "game-fi.habbo.com":
                figureDataUrl = "https://www.habbo.fi/gamedata/figuredata/1";
                break;
            case "game-es.habbo.com":
                figureDataUrl = "https://www.habbo.es/gamedata/figuredata/1";
                break;
            case "game-it.habbo.com":
                figureDataUrl = "https://www.habboit/gamedata/figuredata/1";
                break;
            case "game-s2.habbo.com":
                figureDataUrl = "https://sandbox.habbo.com/gamedata/figuredata/1";
                break;
            default:
        }

        try {
            String xml = IOUtils.toString(new URL(figureDataUrl).openStream(), StandardCharsets.UTF_8);
            figureDataJson = XML.toJSONObject(xml);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getRandomFigure(HGender gender) {
        JSONArray settypes = figureDataJson
                .getJSONObject("figuredata")
                .getJSONObject("sets")
                .getJSONArray("settype");

        String figure = "";

        return settypes.toList().stream()
                .map(s -> (HashMap<String, Object>) s)
                .map(settype -> {
                    String type = (String) settype.get("type");
                    int paletteId = (int) settype.get("paletteid");

                    return type + "-" + getRandomClothingItem(type, gender) + "-" + getRandomColorId(paletteId) + "-" + getRandomColorId(paletteId);
                })
                .collect(Collectors.joining("."));
    }

    private int getRandomColorId(int paletteId) {
        JSONArray palette = figureDataJson
                .getJSONObject("figuredata")
                .getJSONObject("colors")
                .getJSONArray("palette");

        List<HashMap<String, Object>> colors = palette
                .toList().stream()
                .map(o -> (HashMap<String, Object>) o)
                .filter(p -> (int) p.get("id") == paletteId)
                .map(p -> (List<HashMap<String, Object>>) p.get("color"))
                .findAny()
                .orElse(new ArrayList());

        List<Integer> colorIdList = colors.stream()
                .filter(p -> (int) p.get("selectable") == 1)
                .filter(p -> (int) p.get("club") == 0 || isHCMember) // Filter out HC colors if not HC member
                .map(p -> (int) p.get("id"))
                .collect(Collectors.toList());

        Collections.shuffle(colorIdList);

        return colorIdList.stream().findFirst().orElse(0);
    }

    private Integer getRandomClothingItem(String type, HGender gender) {
        JSONArray settypes = figureDataJson
                .getJSONObject("figuredata")
                .getJSONObject("sets")
                .getJSONArray("settype");

        HashMap<String, Object> settype = settypes
                .toList().stream()
                .map(o -> (HashMap<String, Object>) o)
                .filter(s -> s.get("type").equals(type))
                .findFirst().orElse(new HashMap<>());

        if((int) settype.get("mand_" + gender.toString().toLowerCase() + "_1") == 0) {
            if(random.nextDouble() > 0.90) {
                return 0;
            }
        }

        List<HashMap<String, Object>> set = (List<HashMap<String, Object>>) settype.get("set");

        List<Integer> itemIdList = set.stream()
                .filter(p -> (int) p.get("selectable") == 1)
                .filter(i -> HGender.fromString((String) i.get("gender")).equals(gender) || HGender.fromString((String) i.get("gender")).equals(HGender.Unisex)) // Filter by gender
                .filter(i -> !i.containsKey("sellable") || (i.containsKey("sellable") && ownedSellableIds.contains((int) i.get("id")))) // Filter out unowned sellables
                .filter(i -> (int) i.get("club") == 0 || isHCMember) // Filter out HC items if not HC member
                .map(i -> (int) i.get("id"))
                .collect(Collectors.toList());

        Collections.shuffle(itemIdList);

        return itemIdList.stream().findFirst().orElse(0);
    }
}
