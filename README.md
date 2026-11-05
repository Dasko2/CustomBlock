# CustomBlock

Plugin Paper (Minecraft 26.1.2, Java 25) pour ajouter blocs, outils, armes, armures et zones de « biome » personnalisés,
sans écrire de code. Tout se règle dans `config.yml` ; les textures se déposent dans `plugins/CustomBlock/textures/`.
Au premier lancement, des textures d'exemple y sont copiées : remplace-les par les tiennes.

Après toute modification : `/customblock reload`. Le plugin régénère le resource pack
(`plugins/CustomBlock/generated/resource-pack.zip`), le renvoie aux joueurs et recharge les crafts.

## Comment sont affichés les blocs

Un bloc posé est un **vrai bloc Minecraft** (un état de bloc-note) dont le modèle est remplacé par le resource pack.
Il n'y a aucune entité : l'éclairage, l'occlusion ambiante, la night vision et le fullbright agissent comme sur un bloc
vanilla, et le coût serveur est nul même avec des milliers de blocs.

À savoir :
- **`plugins/CustomBlock/generated/block-states.yml` ne doit jamais être supprimé** (ni modifié à la main) : il retient
  quel état correspond à quel bloc. Sans lui, les blocs déjà posés changeraient d'apparence.
- Capacité : 768 états, soit 768 blocs simples (un tronc en consomme 3).
- L'**accordage des blocs-note est désactivé** (`disable-note-tuning: true`) : les blocs-note vanilla restent à la note 0.
- Le resource pack est indispensable pour voir les blocs ; sans lui, ce sont des blocs-note.
- Le clic milieu en créatif (« pick block ») donne un bloc-note vanilla, pas ton bloc : utilise `/cb`.

## Blocs

```yaml
blocks:
  ruby_block:
    base-material: REDSTONE_BLOCK     # matériau de l'ITEM dans l'inventaire
    name: "<red>Bloc de Rubis"
    textures:                         # chaque face peut être différente
      top: ruby_top.png
      bottom: ruby_bottom.png
      side: ruby_side.png
    hardness: 5.0                     # dureté (0 = instantané, négatif = incassable)
    tool: PICKAXE                     # outil requis pour obtenir les drops
    min-tier: IRON                    # niveau minimal de l'outil
    drops:
      - item: ruby                    # objet custom
        amount: 1-3
      - item: DIAMOND                 # ou item vanilla
        chance: 10                    # en %
      - item: ruby_block              # le bloc lui-même (son propre ID)
        silk-touch: true              # true = seulement avec Toucher de soie, false = seulement sans, absent = toujours
```

Sans section `drops`, le bloc se lâche lui-même (désactivable avec `drop-self: false`).

- **Textures** : `texture: x.png` (6 faces identiques), `top/bottom/side`, ou `up/down/north/south/east/west`.
- **Troncs** : `orientation: AXIS` : le bloc se pose debout ou couché selon la face cliquée (`top`/`bottom` = bouts, `side` = écorce).
- **Dureté** : le temps de minage suit la formule vanilla avec ta dureté (outil, niveau, Efficacité, Hâte, Fatigue, eau
  et sol sont pris en compte). Une dureté de 3 se mine comme un bloc vanilla de dureté 3.

## Objets (outils, armes, armures)

```yaml
items:
  ruby_sword:
    material: DIAMOND_SWORD
    name: "<red>Épée de Rubis"
    texture: ruby_sword.png            # icône (inventaire, au sol)
    held-texture: ruby_sword_held.png  # texture en main
    durability: 2500
    attributes:
      attack-damage: 9
      attack-speed: 1.6

  ruby_chestplate:
    material: DIAMOND_CHESTPLATE
    texture: ruby_chestplate.png
    armor-texture: ruby                # textures portées : ruby_layer_1.png + ruby_layer_2.png
    attributes:
      armor: 8                         # protection
      armor-toughness: 2               # solidité
      knockback-resistance: 0.1
```

### Armures : les 3 sortes de textures

| Fichier | Rôle |
|---|---|
| `texture: ruby_helmet.png` | l'icône de l'objet (inventaire, sol) |
| `ruby_layer_1.png` | l'armure portée sur le joueur : casque, plastron, bottes |
| `ruby_layer_2.png` | l'armure portée : jambières uniquement |

`armor-texture: ruby` dit au plugin de prendre `ruby_layer_1.png` et `ruby_layer_2.png`. Toutes les pièces d'un set
utilisent le même `armor-texture`. Les attributs remplacent ceux du matériau d'origine : indique tout ce dont l'objet a besoin.

### Barrières invisibles

```yaml
items:
  invisible_wall:
    material: BARRIER
    name: "<gray>Barrière invisible"
    wall-height: 2        # blocs BARRIER empilés à la pose (1 à 8) : 2 ou plus = impossible de sauter par-dessus
```

Ce sont de vrais blocs barrière vanilla (invisibles, aucun resource pack nécessaire), posés en colonne. Ils bloquent
joueurs et mobs. En créatif, **Maj + casser** une barrière retire aussi celles empilées au-dessus.

## Crafts

Sous-section `recipe` d'un bloc ou d'un objet. Un ingrédient est un ID custom ou un matériau vanilla.

```yaml
recipe:
  type: shaped
  shape:
    - "RRR"
    - " S "
    - " S "
  ingredients:
    R: ruby
    S: STICK
```

`type: shapeless` avec `ingredients: [ruby, GOLD_INGOT]` pour un craft sans forme. `amount` règle la quantité obtenue.

## Biomes

Des zones où l'herbe, la terre et les arbres du monde sont remplacés par tes blocs.

```yaml
biomes:
  ruby_forest:
    worlds: [world]
    based-on: [FOREST, BIRCH_FOREST, PLAINS]   # biomes vanilla où la zone peut apparaître
    scale: 96                                  # taille approximative des zones (blocs)
    coverage: 0.4                              # part approximative du terrain couvert
    clear-vegetation: true                     # retire les arbres et plantes vanilla de la zone
    surface:
      top: ruby_grass                          # remplace l'herbe
      under: ruby_dirt                         # remplace la terre dessous
      under-depth: 3
    trees:
      - log: ruby_log
        leaves: ruby_leaves
        height: 5-8
        leaf-radius: 3
        density: 0.03
```

- Les zones sont générées dans les **nouveaux chunks**. Pour les chunks déjà explorés : `/cb biome apply [rayon]`
  (rayon en chunks, 1 à 6, 3 par défaut).
- Ce ne sont **pas de vrais biomes Minecraft** : ciel, brouillard, mobs et teinte de l'eau restent ceux du biome vanilla.
- Les feuilles sont des blocs pleins sans chute naturelle. Les arbres vanilla coupés en bordure de zone peuvent laisser
  quelques feuilles isolées.

## Menu

`/cb` (ou `/customblock`) ouvre un menu avec tous tes blocs et objets, classés en Blocs, Armures, Armes, Outils et
Objets (plus « Tout »). Clic = 1 exemplaire dans ton inventaire, Maj + clic = une pile complète. Les listes sont
paginées. Le classement des objets se fait d'après leur `material` (`_HELMET`, `_SWORD`, `_PICKAXE`...).

## Resource pack

- `pack-format: auto` : le numéro de format est lu dans ton serveur, rien à régler.
- **Hébergement intégré** : renseigne `resource-pack.self-host.host` (IP publique ou domaine) et ouvre le port TCP
  `resource-pack.self-host.port` (8163 par défaut).
- **Hébergement externe** : renseigne `resource-pack.external-url` avec l'URL directe du `.zip`.

## Commandes (permission `customblock.admin`)

| Commande | Rôle |
|---|---|
| `/cb` ou `/cb menu` | Ouvre le menu de tous les blocs et objets |
| `/cb give <joueur> <id> [quantité]` | Donne un bloc ou un objet (ou un item vanilla) |
| `/cb list` | Liste les blocs et objets |
| `/cb reload` | Recharge la config, régénère et renvoie le pack, recharge les crafts |
| `/cb pack [send]` | Envoie le pack (à soi, ou `send` à tous) |
| `/cb biome apply [rayon]` | Applique les biomes aux chunks déjà générés autour de toi |

## Compilation

Le workflow GitHub Actions compile à chaque push (Gradle, Java 25) et publie le `.jar` dans les artefacts du run.
En local : `gradle build`, le jar est dans `build/libs/`.
