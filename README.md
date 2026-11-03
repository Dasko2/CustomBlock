# CustomBlock

Plugin Paper (Minecraft 26.1.2, Java 25) pour ajouter des blocs et textures personnalisés, sans écrire de code.

## Ajouter un bloc

1. Dépose ta texture PNG (ex. 16x16) dans `plugins/CustomBlock/textures/`.
2. Ajoute une entrée dans `plugins/CustomBlock/config.yml` :

   ```yaml
   blocks:
     mon_bloc:
       base-material: STONE_BRICKS
       name: "<gold>Mon bloc"
       texture: mon_bloc.png
   ```

3. Fais `/customblock reload`.

Le plugin génère le resource pack (`plugins/CustomBlock/generated/resource-pack.zip`), l'héberge et l'envoie
à tous les joueurs. Il est aussi envoyé à chaque connexion.

## Resource pack

- **Hébergement intégré** : renseigne `resource-pack.self-host.host` (IP publique ou domaine) et ouvre le port TCP
  `resource-pack.self-host.port` (8163 par défaut).
- **Hébergement externe** : renseigne `resource-pack.external-url` avec l'URL directe du `.zip`.
- `resource-pack.pack-format` doit correspondre à ta version de Minecraft.

## Commandes (permission `customblock.admin`)

| Commande | Rôle |
|---|---|
| `/customblock give <joueur> <id> [quantité]` | Donne un bloc |
| `/customblock list` | Liste les blocs |
| `/customblock reload` | Recharge la config, régénère et renvoie le pack |
| `/customblock pack [send]` | Envoie le pack (à soi, ou `send` à tous) |

## Compilation

Le workflow GitHub Actions compile à chaque push (Gradle, Java 25) et publie le `.jar` dans les artefacts du run.
En local : `gradle build`, le jar est dans `build/libs/`.
