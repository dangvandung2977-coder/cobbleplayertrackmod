package com.cobbleverse.cobblesyncbridge.cobblemon;

import com.cobbleverse.cobblesyncbridge.CobbleSyncBridgeMod;
import com.cobbleverse.cobblesyncbridge.backend.dto.PokedexEntryPayload;
import com.cobbleverse.cobblesyncbridge.backend.dto.PokemonPayload;
import com.cobbleverse.cobblesyncbridge.backend.dto.StatBlockPayload;
import com.cobbleverse.cobblesyncbridge.util.ReflectionUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CobblemonReflectionAdapter {
    private static final List<String> SINGLETON_CLASS_CANDIDATES = List.of(
            "com.cobblemon.mod.common.Cobblemon"
    );
    private static final Pattern TRANSLATION_KEY_PATTERN = Pattern.compile("key=['\"]([^'\"]+)['\"]");
    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile("([a-z0-9_.-]+):([a-z0-9_/.-]+)");

    private final Set<UUID> missingPartyWarned = ConcurrentHashMap.newKeySet();
    private final Set<UUID> missingPokedexWarned = ConcurrentHashMap.newKeySet();

    public CobblemonSnapshot snapshot(ServerPlayerEntity player) {
        List<PokemonPayload> party = tryExtractParty(player);
        List<PokedexEntryPayload> pokedex = tryExtractPokedex(player);
        return new CobblemonSnapshot(party, pokedex);
    }

    public String inspectRuntime() {
        StringBuilder out = new StringBuilder();
        out.append("CobbleSyncBridge reflection inspect\n");

        for (String candidate : SINGLETON_CLASS_CANDIDATES) {
            out.append("- class ").append(candidate).append(": ");
            Optional<Class<?>> clazzOpt = ReflectionUtils.loadClass(candidate);
            if (clazzOpt.isEmpty()) {
                out.append("not found\n");
                continue;
            }

            Class<?> clazz = clazzOpt.get();
            out.append("found\n");
            ReflectionUtils.getStaticFieldValue(clazz, "INSTANCE").ifPresent(instance -> {
                out.append("  singleton via INSTANCE found\n");
                for (Method method : ReflectionUtils.allMethods(instance.getClass())) {
                    out.append("    ").append(method.getName())
                            .append("(").append(method.getParameterCount()).append(" params)")
                            .append(" -> ").append(method.getReturnType().getSimpleName())
                            .append("\n");
                }
            });
        }

        return out.toString();
    }

    private List<PokemonPayload> tryExtractParty(ServerPlayerEntity player) {
        try {
            Object maybeStore = findPartyStore(player);
            if (maybeStore == null) {
                if (missingPartyWarned.add(player.getUuid())) {
                    CobbleSyncBridgeMod.LOGGER.warn("[CobbleSyncBridge] Party store not found for {}", player.getName().getString());
                }
                return List.of();
            }
            missingPartyWarned.remove(player.getUuid());

            List<?> pokemonObjects = unwrapPokemonCollection(maybeStore);
            List<PokemonPayload> out = new ArrayList<>();
            int slot = 1;
            for (Object pokemon : pokemonObjects) {
                if (pokemon == null) continue;
                out.add(mapPokemon(slot++, pokemon));
            }
            return out;
        } catch (Exception e) {
            CobbleSyncBridgeMod.LOGGER.error("[CobbleSyncBridge] Failed to extract party for {}", player.getName().getString(), e);
            return List.of();
        }
    }

    private List<PokedexEntryPayload> tryExtractPokedex(ServerPlayerEntity player) {
        try {
            Object dex = findPokedexContainer(player);
            if (dex == null) {
                if (missingPokedexWarned.add(player.getUuid())) {
                    CobbleSyncBridgeMod.LOGGER.warn("[CobbleSyncBridge] Pokedex container not found for {}", player.getName().getString());
                }
                return List.of();
            }
            missingPokedexWarned.remove(player.getUuid());

            List<PokedexEntryPayload> out = mapPokedexContainer(dex);

            // If we failed to map entry objects directly, try a JSON-shaped fallback.
            if (out.isEmpty() && dex instanceof JsonObject jsonObject) {
                JsonObject root = jsonObject;
                JsonElement entriesElement = root.get("entries");
                if (entriesElement != null && entriesElement.isJsonArray()) {
                    entriesElement.getAsJsonArray().forEach(el -> {
                        if (el.isJsonObject()) {
                            PokedexEntryPayload mapped = mapDexEntry(el.getAsJsonObject());
                            if (mapped != null) out.add(mapped);
                        }
                    });
                }
            }

            return out;
        } catch (Exception e) {
            CobbleSyncBridgeMod.LOGGER.error("[CobbleSyncBridge] Failed to extract pokedex for {}", player.getName().getString(), e);
            return List.of();
        }
    }

    private Object findPartyStore(ServerPlayerEntity player) {
        for (String singletonClassName : SINGLETON_CLASS_CANDIDATES) {
            Optional<Class<?>> clazzOpt = ReflectionUtils.loadClass(singletonClassName);
            if (clazzOpt.isEmpty()) continue;

            Optional<Object> singleton = ReflectionUtils.getStaticFieldValue(clazzOpt.get(), "INSTANCE");
            if (singleton.isEmpty()) continue;

            Object root = singleton.get();

            // Strategy A: direct methods on singleton
            Optional<Object> directParty = ReflectionUtils.findAndInvoke(root, m -> {
                String name = m.getName().toLowerCase(Locale.ROOT);
                return name.contains("party") && m.getParameterCount() == 1;
            }, player);
            if (directParty.isPresent()) return directParty.get();

            directParty = ReflectionUtils.findAndInvoke(root, m -> {
                String name = m.getName().toLowerCase(Locale.ROOT);
                return name.contains("party") && m.getParameterCount() == 1;
            }, player.getUuid());
            if (directParty.isPresent()) return directParty.get();

            // Strategy B: storage-like child object
            Optional<Object> storage = ReflectionUtils.findAndInvoke(root, m -> {
                String name = m.getName().toLowerCase(Locale.ROOT);
                return m.getParameterCount() == 0 && (
                        name.contains("storage") ||
                        name.contains("pokemonstorage") ||
                        name.contains("storages")
                );
            });

            if (storage.isPresent()) {
                Object storageObj = storage.get();

                Optional<Object> party = ReflectionUtils.findAndInvoke(storageObj, m -> {
                    String name = m.getName().toLowerCase(Locale.ROOT);
                    return m.getParameterCount() == 1 && (
                            name.contains("party") ||
                            name.contains("getplayerparty") ||
                            name.contains("partystore")
                    );
                }, player);
                if (party.isPresent()) return party.get();

                party = ReflectionUtils.findAndInvoke(storageObj, m -> {
                    String name = m.getName().toLowerCase(Locale.ROOT);
                    return m.getParameterCount() == 1 && (
                            name.contains("party") ||
                            name.contains("getplayerparty") ||
                            name.contains("partystore")
                    );
                }, player.getUuid());
                if (party.isPresent()) return party.get();
            }
        }
        return null;
    }

    private Object findPokedexContainer(ServerPlayerEntity player) {
        for (String singletonClassName : SINGLETON_CLASS_CANDIDATES) {
            Optional<Class<?>> clazzOpt = ReflectionUtils.loadClass(singletonClassName);
            if (clazzOpt.isEmpty()) continue;

            Optional<Object> singleton = ReflectionUtils.getStaticFieldValue(clazzOpt.get(), "INSTANCE");
            if (singleton.isEmpty()) continue;

            Object root = singleton.get();

            Optional<Object> directDex = ReflectionUtils.findAndInvoke(root, m -> {
                String name = m.getName().toLowerCase(Locale.ROOT);
                return name.contains("pokedex") && m.getParameterCount() == 1;
            }, player);
            if (directDex.isPresent()) return directDex.get();

            directDex = ReflectionUtils.findAndInvoke(root, m -> {
                String name = m.getName().toLowerCase(Locale.ROOT);
                return name.contains("pokedex") && m.getParameterCount() == 1;
            }, player.getUuid());
            if (directDex.isPresent()) return directDex.get();

            Optional<Object> manager = ReflectionUtils.callIfPresent(
                    root,
                    "getPlayerDataManager",
                    "playerDataManager",
                    "getPlayerInstancedDataStoreManager",
                    "getPlayerData"
            );
            if (manager.isEmpty()) {
                manager = ReflectionUtils.readField(root, "playerDataManager", "playerData");
            }
            if (manager.isEmpty()) {
                manager = ReflectionUtils.findAndInvoke(root, m -> {
                    String name = m.getName().toLowerCase(Locale.ROOT);
                    return m.getParameterCount() == 0 && (
                            name.contains("playerdatamanager") ||
                            name.contains("playerdata") ||
                            name.contains("datamanager") ||
                            name.contains("pokedex") ||
                            name.contains("dex")
                    );
                });
            }

            if (manager.isPresent()) {
                Object managerObj = manager.get();

                Optional<Object> dex = findPokedexDataOnManager(managerObj, player);
                if (dex.isPresent()) return dex.get();
            }

            manager = ReflectionUtils.findAndInvoke(root, m -> {
                String name = m.getName().toLowerCase(Locale.ROOT);
                return m.getParameterCount() == 0 && (
                        name.contains("pokedex") ||
                        name.contains("dex")
                );
            });

            if (manager.isPresent()) {
                Object managerObj = manager.get();

                Optional<Object> dex = ReflectionUtils.findAndInvoke(managerObj, m -> {
                    String name = m.getName().toLowerCase(Locale.ROOT);
                    return m.getParameterCount() == 1 && (
                            name.contains("player") ||
                            name.contains("get") ||
                            name.contains("dex")
                    );
                }, player);
                if (dex.isPresent()) return dex.get();

                dex = ReflectionUtils.findAndInvoke(managerObj, m -> {
                    String name = m.getName().toLowerCase(Locale.ROOT);
                    return m.getParameterCount() == 1 && (
                            name.contains("player") ||
                            name.contains("get") ||
                            name.contains("dex")
                    );
                }, player.getUuid());
                if (dex.isPresent()) return dex.get();
            }
        }
        return null;
    }

    private Optional<Object> findPokedexDataOnManager(Object managerObj, ServerPlayerEntity player) {
        Optional<Object> dex = ReflectionUtils.findAndInvoke(managerObj, m -> {
            String name = m.getName().toLowerCase(Locale.ROOT);
            return m.getParameterCount() == 1 && name.contains("pokedex");
        }, player);
        if (dex.isPresent()) return dex;

        return ReflectionUtils.findAndInvoke(managerObj, m -> {
            String name = m.getName().toLowerCase(Locale.ROOT);
            return m.getParameterCount() == 1 && name.contains("pokedex");
        }, player.getUuid());
    }

    private List<?> unwrapPokemonCollection(Object maybeStore) {
        if (maybeStore == null) return List.of();

        if (maybeStore instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (maybeStore instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object o : iterable) out.add(o);
            return out;
        }

        for (String methodName : List.of("getSlots", "toList", "getAll", "getPokemon", "getTeam", "getContents")) {
            Optional<Object> result = ReflectionUtils.callNoArgs(maybeStore, methodName);
            if (result.isPresent()) {
                return ReflectionUtils.toList(result.get());
            }
        }

        return List.of(maybeStore);
    }

    private PokemonPayload mapPokemon(int slot, Object pokemon) {
        JsonObject saveJson = trySavePokemonJson(pokemon);

        String species = coalesce(
                readJsonString(saveJson, "species"),
                readJsonString(saveJson, "speciesName"),
                readJsonString(saveJson, "species_id"),
                nestedJsonString(saveJson, "species", "name"),
                reflectString(pokemon, "getSpecies", "species")
        );

        Integer dexNumber = readDexNumber(saveJson, pokemon);

        String nickname = coalesce(
                readJsonString(saveJson, "nickname"),
                reflectString(pokemon, "getNickname", "nickname")
        );

        Integer level = coalesceInt(
                readJsonInt(saveJson, "level"),
                reflectInt(pokemon, "getLevel", "level")
        );

        String gender = coalesce(
                readJsonString(saveJson, "gender"),
                reflectString(pokemon, "getGender", "gender")
        );

        String nature = readNature(saveJson, pokemon);

        String ability = readAbility(saveJson, pokemon);

        String heldItem = readHeldItem(saveJson, pokemon);

        String form = coalesce(
                readJsonString(saveJson, "form"),
                nestedJsonString(saveJson, "form", "name"),
                reflectString(pokemon, "getForm", "form")
        );

        boolean shiny = coalesceBoolean(
                readJsonBoolean(saveJson, "shiny"),
                reflectBoolean(pokemon, "getShiny", "isShiny", "shiny")
        );

        List<String> moves = readMoves(saveJson, pokemon);

        StatBlockPayload ivs = readStatBlock(saveJson, "ivs", pokemon, "ivs");
        StatBlockPayload evs = readStatBlock(saveJson, "evs", pokemon, "evs");
        StatBlockPayload stats = readStatBlock(saveJson, "stats", pokemon, "stats");

        Integer hpCurrent = coalesceInt(
                readJsonInt(saveJson, "currentHealth"),
                readJsonInt(saveJson, "hpCurrent"),
                reflectInt(pokemon, "getCurrentHealth", "currentHealth"),
                reflectInt(pokemon, "getCurrentHp", "currentHp"),
                reflectInt(pokemon, "getHealth", "health"),
                reflectInt(pokemon, "getHp", "hp")
        );

        Integer hpMax = coalesceInt(
                readJsonInt(saveJson, "maxHealth"),
                readJsonInt(saveJson, "hpMax"),
                reflectInt(pokemon, "getMaxHealth", "maxHealth"),
                reflectInt(pokemon, "getMaxHp", "maxHp"),
                reflectInt(pokemon, "getHpMax", "hpMax")
        );

        return new PokemonPayload(
                slot,
                species,
                dexNumber,
                nickname,
                level,
                gender,
                nature,
                ability,
                heldItem,
                form,
                shiny,
                moves,
                ivs,
                evs,
                stats,
                hpCurrent,
                hpMax
        );
    }

    private List<PokedexEntryPayload> mapPokedexContainer(Object dex) {
        List<PokedexEntryPayload> out = new ArrayList<>();

        Optional<Object> speciesRecords = ReflectionUtils.callIfPresent(
                dex,
                "getSpeciesRecords",
                "speciesRecords",
                "getRecords",
                "records"
        );
        if (speciesRecords.isEmpty()) {
            speciesRecords = ReflectionUtils.readField(dex, "speciesRecords", "records");
        }
        if (speciesRecords.isPresent()) {
            Object raw = speciesRecords.get();
            if (raw instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    PokedexEntryPayload mapped = mapSpeciesDexRecord(entry.getKey(), entry.getValue());
                    if (mapped != null) out.add(mapped);
                }
            } else {
                for (Object entry : ReflectionUtils.toList(raw)) {
                    PokedexEntryPayload mapped = mapDexEntry(entry);
                    if (mapped != null) out.add(mapped);
                }
            }
        }

        if (!out.isEmpty()) return out;

        List<?> entries = ReflectionUtils.toList(ReflectionUtils.findAndInvoke(
                dex,
                m -> {
                    String name = m.getName().toLowerCase(Locale.ROOT);
                    return m.getParameterCount() == 0 && (
                            name.contains("entries") ||
                            name.contains("records") ||
                            name.contains("caught") ||
                            name.contains("unlocked") ||
                            name.contains("seen")
                    );
                }
        ).orElse(dex));

        for (Object entry : entries) {
            PokedexEntryPayload mapped = mapDexEntry(entry);
            if (mapped != null) out.add(mapped);
        }
        return out;
    }

    private PokedexEntryPayload mapSpeciesDexRecord(Object speciesKey, Object record) {
        if (record == null) return null;

        Object recordId = readObject(record, List.of("getId", "id")).orElse(null);
        Object speciesRef = coalesceObject(
                speciesKey,
                recordId,
                readObject(record, List.of("getSpeciesId", "speciesId")).orElse(null),
                readObject(record, List.of("getSpecies", "species")).orElse(null)
        );
        Object speciesObject = resolveSpeciesObject(speciesRef, recordId, speciesKey);

        String species = coalesce(
                readSpeciesName(speciesObject),
                cleanSpeciesName(stringify(speciesRef)),
                reflectString(record, "getId", "id"),
                reflectString(record, "getSpeciesId", "speciesId"),
                reflectString(record, "getSpecies", "species")
        );
        species = cleanSpeciesName(species);

        Integer dexNumber = coalesceInt(
                readDexNumberFromSpecies(speciesObject),
                reflectIntAny(record, "getDexNumber", "dexNumber", "getNationalDex", "nationalDexNumber", "getNationalPokedexNumber", "nationalPokedexNumber"),
                readDexNumberForSpecies(speciesRef),
                readDexNumberForSpecies(species)
        );

        String knowledge = coalesce(
                reflectString(record, "getKnowledge", "knowledge"),
                reflectString(record, "knowledge", "knowledge")
        );
        String normalizedKnowledge = knowledge != null ? knowledge.toUpperCase(Locale.ROOT) : null;
        boolean caught = "CAUGHT".equals(normalizedKnowledge);
        boolean seen = caught
                || "ENCOUNTERED".equals(normalizedKnowledge)
                || "SEEN".equals(normalizedKnowledge);
        boolean unlocked = seen || caught;

        if ((species == null || species.isBlank()) && dexNumber == null) return null;
        return new PokedexEntryPayload(species, dexNumber, unlocked, caught, seen);
    }

    private PokedexEntryPayload mapDexEntry(Object entry) {
        if (entry == null) return null;
        if (entry instanceof Map.Entry<?, ?> mapEntry) {
            return mapSpeciesDexRecord(mapEntry.getKey(), mapEntry.getValue());
        }
        if (entry instanceof JsonObject json) {
            return mapDexEntry(json);
        }

        Object speciesRef = coalesceObject(
                readObject(entry, List.of("getSpeciesId", "speciesId")).orElse(null),
                readObject(entry, List.of("getSpecies", "species")).orElse(null),
                readObject(entry, List.of("getId", "id")).orElse(null)
        );
        Object speciesObject = resolveSpeciesObject(speciesRef);

        String species = coalesce(
                readSpeciesName(speciesObject),
                reflectString(entry, "getId", "id"),
                reflectString(entry, "getSpeciesId", "speciesId"),
                reflectString(entry, "getSpecies", "species"),
                reflectString(entry, "getSpeciesName", "speciesName"),
                reflectString(entry, "getPokemon", "pokemon")
        );
        species = cleanSpeciesName(species);
        Integer dexNumber = coalesceInt(
                reflectInt(entry, "getDexNumber", "dexNumber"),
                reflectInt(entry, "getNationalDex", "nationalDexNumber"),
                reflectInt(entry, "getNationalPokedexNumber", "nationalPokedexNumber"),
                readDexNumberFromSpecies(speciesObject),
                readDexNumberForSpecies(speciesRef),
                readDexNumberForSpecies(species)
        );
        String knowledge = coalesce(
                reflectString(entry, "getKnowledge", "knowledge"),
                reflectString(entry, "knowledge", "knowledge")
        );
        String normalizedKnowledge = knowledge != null ? knowledge.toUpperCase(Locale.ROOT) : null;
        Boolean unlocked = coalesceBooleanObj(
                reflectBoolean(entry, "isUnlocked", "getUnlocked", "unlocked"),
                normalizedKnowledge != null ? !"NONE".equals(normalizedKnowledge) : null
        );
        Boolean caught = coalesceBooleanObj(
                reflectBoolean(entry, "isCaught", "getCaught", "caught"),
                normalizedKnowledge != null ? "CAUGHT".equals(normalizedKnowledge) : null
        );
        Boolean seen = coalesceBooleanObj(
                reflectBoolean(entry, "isSeen", "getSeen", "seen"),
                normalizedKnowledge != null ? !"NONE".equals(normalizedKnowledge) : null
        );

        if (species == null && dexNumber == null) return null;
        return new PokedexEntryPayload(
                species,
                dexNumber,
                unlocked != null ? unlocked : true,
                caught != null ? caught : unlocked != null && unlocked,
                seen
        );
    }

    private PokedexEntryPayload mapDexEntry(JsonObject json) {
        String species = coalesce(
                readJsonString(json, "species"),
                readJsonString(json, "speciesId"),
                readJsonString(json, "id"),
                nestedJsonString(json, "species", "name")
        );
        species = cleanSpeciesName(species);
        Integer dexNumber = coalesceInt(
                readJsonInt(json, "dexNumber"),
                readJsonInt(json, "nationalPokedexNumber"),
                readJsonInt(json, "nationalDexNumber"),
                nestedJsonInt(json, "species", "nationalPokedexNumber"),
                nestedJsonInt(json, "species", "nationalDexNumber"),
                readDexNumberForSpecies(species)
        );
        boolean unlocked = coalesceBoolean(
                readJsonBoolean(json, "unlocked"),
                readJsonBoolean(json, "caught"),
                true
        );
        boolean caught = coalesceBoolean(
                readJsonBoolean(json, "caught"),
                unlocked
        );
        Boolean seen = readJsonBoolean(json, "seen");
        if (species == null && dexNumber == null) return null;
        return new PokedexEntryPayload(species, dexNumber, unlocked, caught, seen);
    }

    private JsonObject trySavePokemonJson(Object pokemon) {
        try {
            Class<?> registryAccess = Class.forName("net.minecraft.registry.RegistryWrapper$WrapperLookup");
        } catch (Throwable ignored) {
            // ignored; just a warm-up for odd environments
        }

        try {
            Class<?> registryAccessClass = Class.forName("net.minecraft.core.RegistryAccess");
            Object empty = registryAccessClass.getField("EMPTY").get(null);
            JsonObject root = new JsonObject();
            Optional<Object> json = ReflectionUtils.findAndInvoke(
                    pokemon,
                    m -> m.getName().equals("saveToJSON") && m.getParameterCount() == 2,
                    empty,
                    root
            );
            if (json.isPresent() && json.get() instanceof JsonObject object) {
                return object;
            }
        } catch (Throwable ignored) {
        }
        return new JsonObject();
    }

    private Integer readDexNumber(JsonObject json, Object pokemon) {
        Integer fromJson = coalesceInt(
                readJsonInt(json, "dexNumber"),
                readJsonInt(json, "nationalPokedexNumber"),
                readJsonInt(json, "nationalDexNumber"),
                nestedJsonInt(json, "species", "nationalPokedexNumber"),
                nestedJsonInt(json, "species", "nationalDexNumber")
        );
        if (fromJson != null && fromJson > 0) return fromJson;

        Integer fromSpecies = reflectIntFromObject(
                pokemon,
                List.of("getSpecies", "species"),
                "getNationalPokedexNumber",
                "getNationalDexNumber",
                "nationalPokedexNumber",
                "nationalDexNumber"
        );
        if (fromSpecies != null && fromSpecies > 0) return fromSpecies;

        Integer fromFormSpecies = reflectIntFromNestedObject(
                pokemon,
                List.of("getForm", "form"),
                List.of("getSpecies", "species"),
                "getNationalPokedexNumber",
                "getNationalDexNumber",
                "nationalPokedexNumber",
                "nationalDexNumber"
        );
        return fromFormSpecies != null && fromFormSpecies > 0 ? fromFormSpecies : null;
    }

    private String readNature(JsonObject json, Object pokemon) {
        return cleanShortKey(coalesce(
                nestedJsonString(json, "nature", "name"),
                nestedJsonString(json, "nature", "id"),
                nestedJsonString(json, "nature", "displayName"),
                readJsonString(json, "nature"),
                reflectString(pokemon, "getEffectiveNature", "effectiveNature"),
                reflectString(pokemon, "getNature", "nature")
        ));
    }

    private String readAbility(JsonObject json, Object pokemon) {
        return cleanShortKey(coalesce(
                nestedJsonString(json, "ability", "name"),
                nestedJsonString(json, "ability", "id"),
                nestedJsonString(json, "ability", "displayName"),
                readJsonString(json, "ability"),
                reflectString(pokemon, "getAbility", "ability")
        ));
    }

    private Integer readDexNumberFromSpecies(Object species) {
        return reflectIntAny(
                species,
                "getNationalPokedexNumber",
                "getNationalDexNumber",
                "nationalPokedexNumber",
                "nationalDexNumber",
                "getDexNumber",
                "dexNumber"
        );
    }

    private Integer readDexNumberForSpecies(Object speciesRef) {
        return readDexNumberFromSpecies(resolveSpeciesObject(speciesRef));
    }

    private Object resolveSpeciesObject(Object... speciesRefs) {
        for (Object speciesRef : speciesRefs) {
            if (speciesRef == null) continue;

            Optional<Object> byIdentifier = callStaticPokemonSpecies("getByIdentifier", speciesRef);
            if (byIdentifier.isPresent()) return byIdentifier.get();

            String rawName = stringify(speciesRef);
            if (rawName == null) continue;

            for (String candidate : candidateSpeciesNames(rawName)) {
                Optional<Object> byName = callStaticPokemonSpecies("getByName", candidate);
                if (byName.isPresent()) return byName.get();
            }
        }
        return null;
    }

    private Optional<Object> callStaticPokemonSpecies(String methodName, Object arg) {
        if (arg == null) return Optional.empty();
        Optional<Class<?>> clazzOpt = ReflectionUtils.loadClass("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
        if (clazzOpt.isEmpty()) return Optional.empty();

        for (Method method : ReflectionUtils.allMethods(clazzOpt.get())) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) continue;
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            try {
                method.setAccessible(true);
                return Optional.ofNullable(method.invoke(null, arg));
            } catch (Throwable ignored) {
            }
        }
        return Optional.empty();
    }

    private List<String> candidateSpeciesNames(String raw) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addSpeciesNameCandidate(candidates, raw);
        addSpeciesNameCandidate(candidates, cleanSpeciesName(raw));
        String stripped = stripNamespace(raw);
        addSpeciesNameCandidate(candidates, stripped);
        addSpeciesNameCandidate(candidates, stripped != null ? stripped.toLowerCase(Locale.ROOT) : null);
        addSpeciesNameCandidate(candidates, stripped != null ? capitalize(stripped.toLowerCase(Locale.ROOT)) : null);
        return new ArrayList<>(candidates);
    }

    private void addSpeciesNameCandidate(Set<String> candidates, String value) {
        if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
            candidates.add(value);
        }
    }

    private String readSpeciesName(Object species) {
        return cleanSpeciesName(reflectString(species, "getName", "name"));
    }

    private String cleanSpeciesName(String raw) {
        if (raw == null) return null;
        String value = extractTranslationKey(raw).orElse(raw).trim();
        if (value.isBlank() || "null".equalsIgnoreCase(value)) return null;

        value = value.replace("cobblemon.species.", "")
                .replace("pokemon.species.", "")
                .replace("species.", "");

        value = stripNamespace(value);
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;

        int lastDot = value.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < value.length() - 1) {
            value = value.substring(lastDot + 1);
        }

        return value.isBlank() ? null : value;
    }

    private String stripNamespace(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;

        Matcher resourceMatcher = RESOURCE_ID_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        if (resourceMatcher.find()) {
            value = resourceMatcher.group(2);
        }

        int lastSlash = value.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < value.length() - 1) {
            value = value.substring(lastSlash + 1);
        }

        int lastColon = value.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < value.length() - 1) {
            value = value.substring(lastColon + 1);
        }

        return value;
    }

    private List<String> readMoves(JsonObject json, Object pokemon) {
        List<String> out = new ArrayList<>();
        JsonElement moves = json.get("moves");
        if (moves == null) moves = json.get("moveSet");
        if (moves != null && moves.isJsonArray()) {
            moves.getAsJsonArray().forEach(el -> {
                if (el.isJsonPrimitive()) {
                    addCleanMove(out, el.getAsString());
                } else if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    String name = coalesce(
                            readJsonString(obj, "id"),
                            readJsonString(obj, "name"),
                            readJsonString(obj, "move"),
                            readJsonString(obj, "translationKey"),
                            nestedJsonString(obj, "displayName", "string"),
                            readJsonString(obj, "displayName")
                    );
                    addCleanMove(out, name);
                }
            });
        }

        if (!out.isEmpty()) return out;

        Optional<Object> moveSet = ReflectionUtils.callIfPresent(pokemon, "getMoveSet", "moveSet");
        if (moveSet.isPresent()) {
            for (Object entry : ReflectionUtils.toList(moveSet.get())) {
                if (entry == null) continue;
                addCleanMove(out, readMoveName(entry));
            }
        }

        return out;
    }

    private void addCleanMove(List<String> out, String raw) {
        String cleaned = cleanMoveName(raw);
        if (cleaned != null && out.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
            out.add(cleaned);
        }
    }

    private String readMoveName(Object entry) {
        if (entry == null) return null;
        if (entry instanceof JsonObject json) {
            return coalesce(
                    readJsonString(json, "id"),
                    readJsonString(json, "name"),
                    readJsonString(json, "move"),
                    readJsonString(json, "translationKey"),
                    nestedJsonString(json, "displayName", "string"),
                    readJsonString(json, "displayName")
            );
        }

        Optional<Object> template = ReflectionUtils.callIfPresent(entry, "getTemplate", "template", "getMoveTemplate", "moveTemplate");
        if (template.isPresent()) {
            String name = readMoveName(template.get());
            if (name != null) return name;
        }

        for (String methodName : List.of("getName", "name", "getId", "id", "getIdentifier", "identifier", "getTranslationKey", "translationKey", "getDisplayName", "displayName")) {
            Optional<Object> value = ReflectionUtils.callNoArgs(entry, methodName);
            if (value.isEmpty()) value = ReflectionUtils.readField(entry, methodName);
            if (value.isPresent()) {
                String name = stringify(value.get());
                if (name != null) return name;
            }
        }

        return stringify(entry);
    }

    private String cleanMoveName(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank() || "null".equalsIgnoreCase(value)) return null;

        value = extractTranslationKey(value).orElse(value);
        value = value.replace("cobblemon.move.", "")
                .replace("pokemon.move.", "")
                .replace("move.", "");

        Matcher resourceMatcher = RESOURCE_ID_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        if (resourceMatcher.find()) {
            value = resourceMatcher.group(2);
        }

        int lastDot = value.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < value.length() - 1) {
            value = value.substring(lastDot + 1);
        }

        value = value.replace("'", "")
                .replace("\"", "")
                .trim();
        return value.isBlank() ? null : value;
    }

    private String readHeldItem(JsonObject json, Object pokemon) {
        String fromJson = cleanHeldItem(coalesce(
                readJsonString(json, "heldItem"),
                readJsonString(json, "held_item"),
                nestedJsonString(json, "heldItem", "id"),
                nestedJsonString(json, "heldItem", "item"),
                nestedJsonString(json, "held_item", "id"),
                nestedJsonString(json, "held_item", "item")
        ));
        if (fromJson != null) return fromJson;

        for (String methodName : List.of("heldItem", "getHeldItem", "heldItemStack", "getHeldItemStack")) {
            Optional<Object> value = ReflectionUtils.callNoArgs(pokemon, methodName);
            if (value.isEmpty()) value = ReflectionUtils.readField(pokemon, methodName);
            if (value.isPresent()) {
                String item = cleanHeldItem(value.get());
                if (item != null) return item;
            }
        }

        return null;
    }

    private String cleanHeldItem(Object raw) {
        if (raw == null) return null;
        if (raw instanceof JsonObject json) {
            return cleanHeldItem(coalesce(
                    readJsonString(json, "id"),
                    readJsonString(json, "item"),
                    readJsonString(json, "name"),
                    readJsonString(json, "translationKey")
            ));
        }

        Optional<Object> isEmpty = ReflectionUtils.callNoArgs(raw, "isEmpty");
        if (isEmpty.isPresent() && isEmpty.get() instanceof Boolean b && b) {
            return null;
        }

        Optional<Object> itemObject = ReflectionUtils.callIfPresent(raw, "getItem", "item", "asItem");
        if (itemObject.isPresent() && itemObject.get() != raw) {
            String item = cleanHeldItem(itemObject.get());
            if (item != null) return item;
        }

        String value = stringify(raw);
        if (value == null) return null;
        value = extractTranslationKey(value).orElse(value).trim();

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.matches("^\\d+\\s+.+")) {
            String[] parts = lower.split("\\s+", 2);
            if ("0".equals(parts[0])) return null;
            lower = parts[1];
            value = value.split("\\s+", 2)[1];
        }

        Matcher resourceMatcher = RESOURCE_ID_PATTERN.matcher(lower);
        if (resourceMatcher.find()) {
            String resourceId = resourceMatcher.group(1) + ":" + resourceMatcher.group(2);
            if (resourceId.endsWith(":air")) return null;
            return resourceId;
        }

        lower = lower.replace("item.minecraft.", "minecraft:")
                .replace("item.cobblemon.", "cobblemon:");
        if (lower.endsWith(":air") || lower.equals("air") || lower.equals("minecraft.air")) {
            return null;
        }

        return lower.isBlank() || "null".equals(lower) ? null : lower;
    }

    private Optional<String> extractTranslationKey(String value) {
        Matcher matcher = TRANSLATION_KEY_PATTERN.matcher(value);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    private String cleanShortKey(String raw) {
        if (raw == null) return null;
        String value = extractTranslationKey(raw).orElse(raw).trim();
        if (value.isBlank() || "null".equalsIgnoreCase(value)) return null;

        value = value.replace("cobblemon.nature.", "")
                .replace("cobblemon.ability.", "")
                .replace("nature.", "")
                .replace("ability.", "");

        Matcher resourceMatcher = RESOURCE_ID_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        if (resourceMatcher.find()) {
            value = resourceMatcher.group(2);
        }

        int lastDot = value.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < value.length() - 1) {
            value = value.substring(lastDot + 1);
        }

        return value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
    }

    private StatBlockPayload readStatBlock(JsonObject json, String key, Object pokemon, String fieldName) {
        JsonObject obj = json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : null;
        if (obj != null) {
            StatBlockPayload fromJson = new StatBlockPayload(
                    readAnyStat(obj, "hp", "HP", "health"),
                    readAnyStat(obj, "atk", "attack", "ATTACK"),
                    readAnyStat(obj, "def", "defence", "defense", "DEFENCE", "DEFENSE"),
                    readAnyStat(obj, "spa", "specialAttack", "special_attack", "spatk", "SPECIAL_ATTACK"),
                    readAnyStat(obj, "spd", "specialDefence", "specialDefense", "special_defence", "special_defense", "spdef", "SPECIAL_DEFENCE", "SPECIAL_DEFENSE"),
                    readAnyStat(obj, "spe", "speed", "SPEED")
            );
            if (hasAnyStat(fromJson)) return fromJson;
        }

        List<String> candidateMethodNames = new ArrayList<>();
        candidateMethodNames.add("get" + capitalize(fieldName));
        if ("ivs".equals(fieldName)) {
            candidateMethodNames.addAll(List.of("getIVs", "ivs", "getIvs", "getIvSpread", "getIndividualValues", "individualValues"));
        } else if ("evs".equals(fieldName)) {
            candidateMethodNames.addAll(List.of("getEVs", "evs", "getEvs", "getEvSpread", "getEffortValues", "effortValues"));
        } else if ("stats".equals(fieldName)) {
            candidateMethodNames.addAll(List.of("getStats", "stats", "getBattleStats", "getStatBlock", "getCalculatedStats", "getFinalStats", "finalStats"));
        }

        for (String methodName : candidateMethodNames) {
            Optional<Object> maybeValue = ReflectionUtils.callIfPresent(pokemon, methodName);
            if (maybeValue.isPresent()) {
                StatBlockPayload payload = readStatLikeObject(maybeValue.get());
                if (hasAnyStat(payload)) return payload;
            }
        }

        Optional<Object> maybeField = ReflectionUtils.readField(pokemon, fieldName);
        if (maybeField.isPresent()) {
            StatBlockPayload payload = readStatLikeObject(maybeField.get());
            if (hasAnyStat(payload)) return payload;
        }

        if ("stats".equals(fieldName)) {
            StatBlockPayload directStats = readDirectPokemonStats(pokemon);
            if (hasAnyStat(directStats)) return directStats;
        }

        return new StatBlockPayload(null, null, null, null, null, null);
    }

    private StatBlockPayload readStatLikeObject(Object raw) {
        if (raw == null) {
            return new StatBlockPayload(null, null, null, null, null, null);
        }

        if (raw instanceof JsonObject jsonObject) {
            return new StatBlockPayload(
                    readAnyStat(jsonObject, "hp", "HP", "health"),
                    readAnyStat(jsonObject, "atk", "attack", "ATTACK"),
                    readAnyStat(jsonObject, "def", "defence", "defense", "DEFENCE", "DEFENSE"),
                    readAnyStat(jsonObject, "spa", "specialAttack", "special_attack", "spatk", "SPECIAL_ATTACK"),
                    readAnyStat(jsonObject, "spd", "specialDefence", "specialDefense", "special_defence", "special_defense", "spdef", "SPECIAL_DEFENCE", "SPECIAL_DEFENSE"),
                    readAnyStat(jsonObject, "spe", "speed", "SPEED")
            );
        }

        if (raw instanceof Map<?, ?> map) {
            return new StatBlockPayload(
                    mapIntAliases(map, "hp", "health"),
                    mapIntAliases(map, "atk", "attack"),
                    mapIntAliases(map, "def", "defence", "defense"),
                    mapIntAliases(map, "spa", "specialattack", "spatk"),
                    mapIntAliases(map, "spd", "specialdefence", "specialdefense", "spdef"),
                    mapIntAliases(map, "spe", "speed")
            );
        }

        StatBlockPayload keyed = readStatsByKeyedGetter(raw);
        if (hasAnyStat(keyed)) return keyed;

        return new StatBlockPayload(
                reflectIntAny(raw, "getHp", "getHP", "getHealth", "hp", "health"),
                reflectIntAny(raw, "getAttack", "getAtk", "attack", "atk"),
                reflectIntAny(raw, "getDefense", "getDefence", "getDef", "defense", "defence", "def"),
                reflectIntAny(raw, "getSpecialAttack", "getSpAtk", "getSpa", "specialAttack", "spatk", "spa"),
                reflectIntAny(raw, "getSpecialDefense", "getSpecialDefence", "getSpDef", "getSpd", "specialDefense", "specialDefence", "spdef", "spd"),
                reflectIntAny(raw, "getSpeed", "getSpe", "speed", "spe")
        );
    }

    private StatBlockPayload readDirectPokemonStats(Object pokemon) {
        return new StatBlockPayload(
                reflectIntAny(pokemon, "getHpStat", "getHPStat", "getHp", "hp"),
                reflectIntAny(pokemon, "getAttackStat", "getAtkStat", "getAttack", "atk"),
                reflectIntAny(pokemon, "getDefenseStat", "getDefenceStat", "getDefStat", "getDefense", "getDefence", "defense", "defence", "def"),
                reflectIntAny(pokemon, "getSpecialAttackStat", "getSpAtkStat", "getSpecialAttack", "spa"),
                reflectIntAny(pokemon, "getSpecialDefenseStat", "getSpecialDefenceStat", "getSpDefStat", "getSpecialDefense", "getSpecialDefence", "specialDefense", "specialDefence", "spd"),
                reflectIntAny(pokemon, "getSpeedStat", "getSpeStat", "getSpeed", "spe")
        );
    }

    private StatBlockPayload readStatsByKeyedGetter(Object raw) {
        return new StatBlockPayload(
                readStatByKey(raw, "hp", "health"),
                readStatByKey(raw, "atk", "attack"),
                readStatByKey(raw, "def", "defence", "defense"),
                readStatByKey(raw, "spa", "specialattack", "spatk"),
                readStatByKey(raw, "spd", "specialdefence", "specialdefense", "spdef"),
                readStatByKey(raw, "spe", "speed")
        );
    }

    private Integer readStatByKey(Object raw, String... aliases) {
        for (Method method : ReflectionUtils.allMethods(raw.getClass())) {
            if (method.getParameterCount() != 1) continue;

            for (Object key : candidateStatKeys(method.getParameterTypes()[0], aliases)) {
                try {
                    method.setAccessible(true);
                    Integer value = coerceInt(method.invoke(raw, key));
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private List<Object> candidateStatKeys(Class<?> keyType, String... aliases) {
        List<Object> keys = new ArrayList<>();
        if (keyType.isEnum()) {
            Object[] constants = keyType.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (statTokenMatches(constant.toString(), aliases)) {
                        keys.add(constant);
                    }
                }
            }
        }

        Optional<Class<?>> cobblemonStats = ReflectionUtils.loadClass("com.cobblemon.mod.common.api.pokemon.stats.Stats");
        if (cobblemonStats.isPresent()) {
            Object[] constants = cobblemonStats.get().getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (constant != null
                            && keyType.isAssignableFrom(constant.getClass())
                            && statTokenMatches(constant.toString(), aliases)) {
                        keys.add(constant);
                    }
                }
            }
        }

        for (java.lang.reflect.Field field : keyType.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (!keyType.isAssignableFrom(field.getType())) continue;
            if (!statTokenMatches(field.getName(), aliases)) continue;
            try {
                keys.add(field.get(null));
            } catch (Throwable ignored) {
            }
        }
        return keys;
    }

    private boolean hasAnyStat(StatBlockPayload payload) {
        return payload.hp() != null
                || payload.atk() != null
                || payload.def() != null
                || payload.spa() != null
                || payload.spd() != null
                || payload.spe() != null;
    }

    private Integer mapIntAliases(Map<?, ?> map, String... aliases) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) continue;
            if (statTokenMatches(entry.getKey().toString(), aliases)) {
                Integer value = coerceInt(entry.getValue());
                if (value != null) return value;
            }
        }
        return null;
    }

    private boolean statTokenMatches(String token, String... aliases) {
        String normalized = normalizeStatToken(token);
        for (String alias : aliases) {
            String normalizedAlias = normalizeStatToken(alias);
            if (normalized.contains("special") && (
                    "attack".equals(normalizedAlias)
                            || "atk".equals(normalizedAlias)
                            || "defense".equals(normalizedAlias)
                            || "defence".equals(normalizedAlias)
                            || "def".equals(normalizedAlias)
            )) {
                continue;
            }
            if (normalized.equals(normalizedAlias)) return true;
            if (normalized.endsWith(normalizedAlias) && normalized.length() > normalizedAlias.length() + 3) return true;
        }
        return false;
    }

    private String normalizeStatToken(String token) {
        return token == null
                ? ""
                : token.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Integer reflectIntAny(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Optional<Object> value = ReflectionUtils.callNoArgs(target, name);
            if (value.isEmpty()) value = ReflectionUtils.readField(target, name);
            if (value.isPresent()) {
                Integer parsed = coerceInt(value.get());
                if (parsed != null) return parsed;
            }
        }
        return null;
    }

    private Integer reflectIntFromObject(Object root, List<String> objectAccessors, String... intAccessors) {
        Optional<Object> object = readObject(root, objectAccessors);
        return object.map(value -> reflectIntAny(value, intAccessors)).orElse(null);
    }

    private Integer reflectIntFromNestedObject(
            Object root,
            List<String> firstAccessors,
            List<String> secondAccessors,
            String... intAccessors
    ) {
        Optional<Object> first = readObject(root, firstAccessors);
        if (first.isEmpty()) return null;
        return reflectIntFromObject(first.get(), secondAccessors, intAccessors);
    }

    private Optional<Object> readObject(Object root, List<String> accessors) {
        if (root == null) return Optional.empty();
        for (String accessor : accessors) {
            Optional<Object> value = ReflectionUtils.callNoArgs(root, accessor);
            if (value.isEmpty()) value = ReflectionUtils.readField(root, accessor);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    private Integer coerceInt(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof Optional<?> optional) return optional.map(this::coerceInt).orElse(null);

        for (String methodName : List.of("intValue", "getValue", "value", "getAmount", "amount", "getTotal", "total", "getStat", "stat")) {
            Optional<Object> value = ReflectionUtils.callNoArgs(raw, methodName);
            if (value.isPresent() && value.get() != raw) {
                Integer parsed = coerceInt(value.get());
                if (parsed != null) return parsed;
            }
        }

        String text = raw.toString();
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
        }

        Matcher matcher = Pattern.compile("-?\\d+").matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Integer readAnyStat(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                try {
                    return obj.get(key).getAsInt();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String reflectString(Object target, String getterName, String fallbackField) {
        if (target == null) return null;
        Optional<Object> value = ReflectionUtils.callNoArgs(target, getterName);
        if (value.isEmpty()) {
            value = ReflectionUtils.readField(target, fallbackField);
        }
        return value.map(this::stringify).orElse(null);
    }

    private String reflectStringFromMethodCall(Object target, String methodName) {
        if (target == null) return null;
        Optional<Object> value = ReflectionUtils.callNoArgs(target, methodName);
        return value.map(this::stringify).orElse(null);
    }

    private Integer reflectInt(Object target, String getterName, String fallbackField) {
        if (target == null) return null;
        Optional<Object> value = ReflectionUtils.callNoArgs(target, getterName);
        if (value.isEmpty()) value = ReflectionUtils.readField(target, fallbackField);
        if (value.isPresent() && value.get() instanceof Number n) return n.intValue();
        return null;
    }

    private Boolean reflectBoolean(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Optional<Object> value = ReflectionUtils.callNoArgs(target, name);
            if (value.isEmpty()) value = ReflectionUtils.readField(target, name);
            if (value.isPresent() && value.get() instanceof Boolean b) return b;
        }
        return null;
    }

    private String stringify(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        Optional<Object> directString = ReflectionUtils.callIfPresent(value, "getString", "asString", "getLiteralString", "literalString");
        if (directString.isPresent() && directString.get() != value) {
            String text = String.valueOf(directString.get());
            if (!text.isBlank()) return text;
        }
        Optional<Object> displayName = ReflectionUtils.callIfPresent(value, "getDisplayName", "displayName");
        if (displayName.isPresent()) {
            Object inner = displayName.get();
            Optional<Object> innerString = ReflectionUtils.callIfPresent(inner, "getString", "asString", "string");
            if (innerString.isPresent()) return String.valueOf(innerString.get());
            return inner.toString();
        }
        Optional<Object> name = ReflectionUtils.callIfPresent(value, "getName", "name", "getId", "id", "getIdentifier", "identifier", "getTranslationKey", "translationKey");
        if (name.isPresent()) return String.valueOf(name.get());
        return value.toString();
    }

    private String readJsonString(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonPrimitive()) return null;
        try {
            return json.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private Integer readJsonInt(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonPrimitive()) return null;
        try {
            return json.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean readJsonBoolean(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonPrimitive()) return null;
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }

    private String nestedJsonString(JsonObject json, String parent, String key) {
        if (json == null || !json.has(parent) || !json.get(parent).isJsonObject()) return null;
        return readJsonString(json.getAsJsonObject(parent), key);
    }

    private Integer nestedJsonInt(JsonObject json, String parent, String key) {
        if (json == null || !json.has(parent) || !json.get(parent).isJsonObject()) return null;
        return readJsonInt(json.getAsJsonObject(parent), key);
    }

    private String coalesce(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private Object coalesceObject(Object... values) {
        for (Object value : values) {
            if (value == null) continue;
            if (value instanceof String s && (s.isBlank() || "null".equalsIgnoreCase(s))) continue;
            return value;
        }
        return null;
    }

    private Integer coalesceInt(Integer... values) {
        for (Integer value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private boolean coalesceBoolean(Boolean first, Boolean second, boolean fallback) {
        if (first != null) return first;
        if (second != null) return second;
        return fallback;
    }

    private boolean coalesceBoolean(Boolean first, boolean fallback) {
        return first != null ? first : fallback;
    }

    private Boolean coalesceBooleanObj(Boolean... values) {
        for (Boolean value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private String capitalize(String in) {
        if (in == null || in.isEmpty()) return in;
        return Character.toUpperCase(in.charAt(0)) + in.substring(1);
    }
}
