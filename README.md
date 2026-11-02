<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHEggDragonBoss

*Deux boss à faire apparaître sur son île par un rituel : le Jeune Ender Dragon et le Creaking.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Chaque boss se lance par un rituel : un clic droit sur un bloc avec un objet (Shift pour donner toute la pile), jusqu'à la quantité requise. Le bloc est alors consommé, un affichage du bloc grossit pendant le compte à rebours, puis le boss apparaît à sa place. Casser le bloc avant le compte à rebours rend les objets donnés ; pendant le compte à rebours, c'est refusé.

| | Boss n°1 : dragon | Boss n°2 : creaking |
|---|---|---|
| Bloc du rituel | `DRAGON_EGG` | `CREAKING_HEART` |
| Objet requis | 15 `ECHO_SHARD` | 10 `RESIN_BRICK` |
| Compte à rebours | 10 s | 10 s |
| Boss | Phantom « Jeune Ender Dragon », sans IA, qui poursuit les joueurs de l'île | Creaking à l'IA vanilla (il se fige quand on le regarde) |
| Combat | Frappe de près et boules de feu de loin | Frappe vanilla, onde de choc et résine collante |
| Particularité | | Invulnérable par phases (66 % puis 33 % des PV) tant qu'il reste des cœurs à détruire à la main autour de lui |
| Récompenses | 1 membrane de phantom, 15 000 XP, commande `rc dragonegg-rewards {player}` | 20 000 XP, commande `rc creaking-rewards {player}` |

Règles communes :

- Une île n'a jamais qu'un seul boss (n'importe lequel) ou rituel en cours à la fois.
- Le rituel demande le privilège d'île `EGG_HATCH` (SuperiorSkyblock2), le même pour les deux boss.
- Les PV de chaque boss grandissent avec le niveau de l'île et le nombre de membres.
- Un boss qui disparaît avec son chunk est mis en sommeil puis réveillé avec ses PV ; après un redémarrage, il est repris avec sa phase.
- À la mort : barre de boss retirée, drops vanilla supprimés, XP donnée au tueur, objets et commandes console de la configuration.

### Le combat du Creaking

- Sous un seuil de PV, le boss devient invulnérable, la barre de boss change de titre et de couleur et affiche les cœurs restants. Des blocs `CREAKING_HEART` non naturels apparaissent sur des blocs d'air de l'île, à quelques blocs de lui.
- Les cœurs se détruisent à la main : un clic gauche retire un point de vie du cœur, sans casser le bloc, donc sans dépendre d'une protection d'île. Explosions, pistons, eau et créatures ne peuvent ni les déplacer ni les casser.
- Quand le dernier cœur tombe, le boss redevient vulnérable. S'il meurt ou disparaît, tous les cœurs restants sont supprimés ; leurs positions sont écrites sur disque pour nettoyer après un arrêt brutal.
- Le boss ne quitte jamais l'île et ne tombe jamais dans le vide : hors de l'île ou trop bas, il est ramené au centre.
- Pendant une phase, il ne frappe qu'en mêlée vanille et lance l'onde de choc plus souvent.

## Dépendances

- Obligatoires : `SuperiorSkyblock2`, `FancyHolograms`

## Configuration

Tout est dans `config.yml`, documenté clé par clé : messages (communs, puis par boss), rituels, sons, boss, privilège d'île. Le fichier est lu au démarrage ; une valeur invalide produit un avertissement dans la console et la valeur livrée est utilisée. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHEggDragonBoss/
└── <a href="src/main/resources/config.yml">config.yml</a>
</pre>

Données écrites par le plugin dans le même dossier : `hatching-data.yml` (rituels en cours), `dormant-bosses.yml` (boss endormis), `creaking-hearts.yml` (positions des cœurs du Creaking) et `logs/` (un journal par jour). Dans les deux premiers, un champ `type` (`dragon` ou `creaking`) est facultatif : son absence signifie `dragon`.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
