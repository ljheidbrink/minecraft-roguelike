package greymerk.roguelike.dungeon.settings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import greymerk.roguelike.config.RogueConfig;
import greymerk.roguelike.dungeon.settings.builtin.SettingsDesertTheme;
import greymerk.roguelike.dungeon.settings.builtin.SettingsForestTheme;
import greymerk.roguelike.dungeon.settings.builtin.SettingsGenerator;
import greymerk.roguelike.dungeon.settings.builtin.SettingsGrasslandTheme;
import greymerk.roguelike.dungeon.settings.builtin.SettingsIceTheme;
import greymerk.roguelike.dungeon.settings.builtin.SettingsJungleTheme;
import greymerk.roguelike.dungeon.settings.builtin.SettingsLootRules;
import greymerk.roguelike.dungeon.settings.builtin.SettingsMesaTheme;
import greymerk.roguelike.dungeon.settings.builtin.SettingsMountainTheme;
import greymerk.roguelike.dungeon.settings.builtin.SettingsRooms;
import greymerk.roguelike.dungeon.settings.builtin.SettingsSecrets;
import greymerk.roguelike.dungeon.settings.builtin.SettingsSegments;
import greymerk.roguelike.dungeon.settings.builtin.SettingsSize;
import greymerk.roguelike.dungeon.settings.builtin.SettingsSwampTheme;
import greymerk.roguelike.dungeon.settings.builtin.SettingsTheme;
import greymerk.roguelike.util.WeightedChoice;
import greymerk.roguelike.util.WeightedRandomizer;
import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.IWorldEditor;

public class SettingsResolver {

	private static final String SETTINGS_DIRECTORY = RogueConfig.configDirName + "/settings";
	private Map<String, DungeonSettings> settings;
	private List<DungeonSettings> builtin;
	private DungeonSettings base;
	
	public SettingsResolver(){
		settings = new HashMap<String, DungeonSettings>();
		DungeonSettings base = new SettingsBlank();
		base = new DungeonSettings(base, new SettingsRooms());
		base = new DungeonSettings(base, new SettingsSecrets());
		base = new DungeonSettings(base, new SettingsSegments());
		base = new DungeonSettings(base, new SettingsSize());
		base = new DungeonSettings(base, new SettingsTheme());
		base = new DungeonSettings(base, new SettingsGenerator());
		base = new DungeonSettings(base, new SettingsLootRules());
		base.setCriteria(new SpawnCriteria());
		this.base = base;

		this.builtin = new ArrayList<DungeonSettings>();
		this.builtin.add(new SettingsDesertTheme());
		this.builtin.add(new SettingsGrasslandTheme());
		this.builtin.add(new SettingsJungleTheme());
		this.builtin.add(new SettingsSwampTheme());
		this.builtin.add(new SettingsMountainTheme());
		this.builtin.add(new SettingsForestTheme());
		this.builtin.add(new SettingsMesaTheme());
		this.builtin.add(new SettingsIceTheme());
		
		File settingsDir = new File(SETTINGS_DIRECTORY);
		if(!settingsDir.exists() || !settingsDir.isDirectory()) return;
		File[] settingsFiles = settingsDir.listFiles();
		Arrays.sort(settingsFiles);
		
		for(int i = 0; i < settingsFiles.length; ++i){
			File toParse = settingsFiles[i];
			DungeonSettings toAdd = null;
			try{
				toAdd = parseFile(toParse);
			} catch (Exception e){
				System.err.println("Error found in file " + toParse.getName());
				System.err.println(e.getMessage());
				continue; // skip this setting
			}
			settings.put(toAdd.getName(), toAdd);
		}
	}
	
	private DungeonSettings parseFile(File toParse) throws Exception{
		String content;
		
		try {
			content = Files.toString(toParse, Charsets.UTF_8);
		} catch (IOException e) {
			throw new Exception("Error reading file");
		}
		
		JsonParser jParser = new JsonParser();
		JsonObject root = null;
		DungeonSettings toAdd = null;
		
		try {
			root = (JsonObject)jParser.parse(content);
		} catch (JsonSyntaxException e){
			
			Throwable cause = e.getCause();
			throw new Exception(cause.getMessage());
		} catch (Exception e){
			throw new Exception("An unknown error occurred while parsing json");
		}
		
		try {
			toAdd = new DungeonSettings(root);
		} catch (Exception e){
			throw new Exception("An error occured while adding " + toAdd.getName());
		}
		
		return toAdd;
	}
	
	public DungeonSettings getByName(String name){
		DungeonSettings override = this.settings.get(name);
		if(override == null) return null;
		return new DungeonSettings(this.base, override);
	}
	
	public ISettings getSettings(IWorldEditor editor, Random rand, Coord pos){
		
		DungeonSettings regular = new DungeonSettings(this.base, this.getBuiltin(editor, rand, pos));
		DungeonSettings custom = this.getCustom(editor, rand, pos);
		
		return new DungeonSettings(regular, custom);
		
	}
	
	private DungeonSettings getBuiltin(IWorldEditor editor, Random rand, Coord pos){
		WeightedRandomizer<DungeonSettings> settingsRandomizer = new WeightedRandomizer<DungeonSettings>();

		for(DungeonSettings setting : this.builtin){
			if(setting.isValid(editor, pos)){
				settingsRandomizer.add(new WeightedChoice<DungeonSettings>(setting, setting.criteria.weight));
			}
		}
		
		DungeonSettings picked = settingsRandomizer.get(rand);
		
		if(picked == null){
			return new DungeonSettings(this.base);
		}
		
		return picked;
	}
	
	private DungeonSettings getCustom(IWorldEditor editor, Random rand, Coord pos){
		
		DungeonSettings custom = new SettingsBlank();
		for(DungeonSettings setting : this.settings.values()){
			if(!setting.isValid(editor, pos)) continue;
			setting = processInheritance(setting, settings);
			custom = new DungeonSettings(custom, setting);
		}
		
		return custom;
	}
	
	public ISettings getDefaultSettings(){
		return new DungeonSettings(base);
	}

	public ISettings getWithDefault(String name) {
		if(!this.settings.containsKey(name)) return null;
		DungeonSettings custom = new DungeonSettings(this.settings.get(name));
		custom = processInheritance(custom, this.settings);
		return new DungeonSettings(this.base, custom);
	}
	
	public static DungeonSettings processInheritance(DungeonSettings toProcess, Map<String, DungeonSettings> settings){
		DungeonSettings setting = new DungeonSettings(toProcess);
		
		for(String name : toProcess.getInherits()){
			if(settings.containsKey(name)){
				DungeonSettings custom = new DungeonSettings(settings.get(name));
				if(!custom.getInherits().isEmpty()){
					custom = processInheritance(custom, settings);
				}
				
				setting = new DungeonSettings(setting, custom);
			}
		}
		
		return setting;
	}
	
	@Override
	public String toString(){
		String s = "";
		for(String key : this.settings.keySet()){
			s += key += " ";
		}
		return s;
	}
}
