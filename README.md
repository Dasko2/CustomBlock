# CustomBlock

Plugin Paper (Minecraft 26.1.2, Java 25) pour ajouter blocs, outils, armes et armures personnalisés,
sans écrire de code. Tout se règle dans `config.yml` ; les textures se déposent dans `plugins/CustomBlock/textures/`.

Après toute modification : `/customblock reload`. Le plugin régénère le resource pack
(`plugins/CustomBlock/generated/resource-pack.zip`), le renvoie aux joueurs et recharge les crafts.

## Blocs

```yaml
blocks:
  ruby_block:
    base-material: REDSTONE_BLOCK
    placed-material: STONE        # hitbox et dureté de base du bloc posé
    name: "<red>Bloc de Rubis"
    textures:                     # chaque face peut être différente
      top: ruby_top.png
      bottom: ruby_bottom.png
      side: ruby_side.png
    hardness: 5.0                 # dureté (0 = instantané, négatif = incassable)
    tool: PICKAXE                 # outil requis pour obtenir les drops
    min-tier: IRON                # niveau minimal de l'outil
    drops:
      - item: ruby                # objet custom
        amount: 1-3
      - item: DIAMOND             # ou item vanilla
        chance: 10                # en %
```

Textures : `texture: x.png` (6 faces identiques), `top/bottom/side`, ou `up/down/north/south/east/west`.

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
    durability: 800
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
utilisent le même `armor-texture`.

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

## Resource pack

- `pack-format: auto` : le numéro de format est lu dans ton serveur, rien à régler.
- **Hébergement intégré** : renseigne `resource-pack.self-host.host` (IP publique ou domaine) et ouvre le port TCP
  `resource-pack.self-host.port` (8163 par défaut).
- **Hébergement externe** : renseigne `resource-pack.external-url` avec l'URL directe du `.zip`.

## Menu

`/cb` (ou `/customblock`) ouvre un menu avec tous tes blocs et objets, classés en Blocs, Armures, Armes, Outils et
Objets (plus « Tout »). Clic = 1 exemplaire dans ton inventaire, Maj + clic = une pile complète. Les listes sont
paginées. Le classement des objets se fait d'après leur `material` (`_HELMET`, `_SWORD`, `_PICKAXE`...).

## Commandes (permission `customblock.admin`)

| Commande | Rôle |
|---|---|
| `/cb` ou `/cb menu` | Ouvre le menu de tous les blocs et objets |
| `/customblock give <joueur> <id> [quantité]` | Donne un bloc ou un objet (ou un item vanilla) |
| `/customblock list` | Liste les blocs et objets |
| `/customblock reload` | Recharge la config, régénère et renvoie le pack, recharge les crafts |
| `/customblock pack [send]` | Envoie le pack (à soi, ou `send` à tous) |

## Compilation

Le workflow GitHub Actions compile à chaque push (Gradle, Java 25) et publie le `.jar` dans les artefacts du run.
En local : `gradle build`, le jar est dans `build/libs/`.
