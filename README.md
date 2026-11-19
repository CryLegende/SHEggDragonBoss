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
| Boss | Phantom « Jeune Ender Dragon », sans IA, qui tourne autour des joueurs de l'île | Creaking à l'IA vanilla (il se fige quand on le regarde) |
| Combat | Boules de feu de loin, morsure de près, plongeon sur un joueur, rugissement à chaque phase | Frappe vanilla, onde de choc, résine collante, le Noir (éclairs d'obscurité, téléportations, un coup par combattant) et le Sursaut (se retourne contre qui le frappe sur place) |
| Particularité | Phases à 75 %, 50 % et 25 % des PV : rugissement invulnérable puis souffle qui repousse ; plus rapide à chaque phase ; étourdi et plus vulnérable après un plongeon | Invulnérable par phases (80 %, 60 %, 40 % puis 20 % des PV) tant qu'il reste des cœurs à détruire à la main autour de lui ; plus de cœurs avec plus de combattants |
| Récompenses (au tueur seul) | XP (`xp-reward`), tirages de `roll-commands` et 1 membrane de phantom | XP (`xp-reward`), commandes garanties (`commands`) et tirages de `roll-commands` |

Règles communes :

- Une île n'a jamais qu'un seul boss (n'importe lequel) ou rituel en cours à la fois.
- Le rituel demande le privilège d'île `EGG_HATCH` (SuperiorSkyblock2), le même pour les deux boss.
- Les PV de chaque boss grandissent avec le niveau de l'île et le nombre de membres.
- Un boss qui disparaît avec son chunk est mis en sommeil puis réveillé avec ses PV ; après un redémarrage, il est repris avec sa phase.
- Un boss ne vise jamais un joueur invisible (vanish : métadonnée `vanished` de PremiumVanish, SuperVanish ou EssentialsX), en créatif ou en spectateur : ni téléportation, ni Noir, ni attaque.
- **Plafond par coup.** Aucun coup ne retire plus de `max-hit-percent` % des PV max du boss : c'est ce plafond qui fixe la durée minimale du combat, quelle que soit la force du coup.
- **Mort en scène.** Le coup fatal est retenu : le boss tremble, s'élève ou s'affaisse en tournant, des particules l'entourent en spirale, puis un final éclate (explosion, éclats, anneaux qui s'élargissent, colonne de lumière) et le plugin porte le coup de grâce. Le vide et `/kill` passent sans scène. Réglable dans `bosses.<boss>.death`.
- **Récompenses.** Elles vont au tueur seul (celui qui porte le coup fatal). À la mort : titre et classement des combattants dans le chat (qui a fait quoi, en %, avec le tueur en dernière ligne), puis pour le tueur l'XP (`xp-reward`), `commands` une fois et `roll-commands` `rolls` fois (`{player}` = le tueur), et les objets lâchés sur place. Chaque coup retire des PV au boss et les compte à son auteur (un cœur brisé compte `hearts.credit-percent` % des PV max) ; ce décompte ne sert qu'au classement et au remplacement du tueur : s'il s'est déconnecté pendant la mort en scène ou si le boss tombe d'une autre cause, le joueur qui a le plus infligé au boss reçoit les récompenses à sa place. Une commande par ligne (pas de `;`).

## Mode réseau

Avec **SHSkyBridge** et **SHNetwork** installés (plusieurs serveurs se partagent les îles, chacune étant hébergée par un seul) et `reseau.actif: true`
(valeur par défaut) :

- un serveur ne reprend, ne réveille et ne supprime que les boss et les rituels des îles qu'il héberge ; un boss trouvé dans une île hébergée ailleurs
  (copie périmée d'un chunk) ou inconnue ici n'est ni repris ni supprimé, un boss dormant d'une île inconnue reste dans `dormant-bosses.yml`, et le bloc
  d'un rituel d'une île hébergée ailleurs n'est pas lu (le chunk n'est pas à jour ici) : l'entrée reste telle quelle dans `hatching-data.yml` ;
- les boss dormants et les rituels d'une île suivent l'île quand SHSkyBridge la déplace (`/skybridge deplacer`) ; les boss en vie sont des entités, déplacées avec les chunks ;
- les commandes de récompense qui contiennent `{player}` passent par SHNetwork : elles s'exécutent là où se trouve le tueur, une fois sa sauvegarde de passage terminée.

Sans eux, ou sans la clé `reseau`, le plugin se comporte comme avant.

### Le combat du dragon

Un Phantom sans IA que le plugin fait voler.

- **Vol en cercle.** Il tourne autour du joueur le plus proche (`flight`) et crache une salve de boules de feu (`ranged-attack`) sur un joueur tiré au hasard ; il mord le joueur qui s'approche trop.
- **Plongeon.** Il se cabre au-dessus d'un joueur tiré au hasard, fixe l'endroit visé (un cercle de flammes au sol le montre, la barre d'action du joueur le prévient), puis fond dessus. L'impact blesse et repousse tout ce qui est autour. Le dragon reste ensuite étourdi (`stun-ticks`) et encaisse `stun-damage-multiplier` fois plus.
- **Rugissement.** À chaque seuil de `phases` il est invulnérable `roar.duration-ticks` ticks, se cabre en tremblant, puis un souffle blesse et repousse tout autour. Il est ensuite plus rapide (`phase-speed-bonus`), enchaîne ses attaques plus vite (`phase-speedup`) et tire une boule de feu de plus (`fireballs-per-phase`). La barre de boss rougit à la deuxième phase.
- La phase franchie est écrite sur l'entité : un redémarrage reprend le combat là où il en était.

### Le combat du Creaking

Le Creaking vanilla se fige tant qu'un joueur le regarde ; le combat joue sur cette règle, face à des joueurs en équipement complet.

- **Le Noir.** Un éclair d'obscurité de moins d'une seconde, sans annonce, qui revient toutes les quelques secondes (`cooldown-ticks` plus ou moins `cooldown-jitter-ticks`, plus court à chaque phase). Il aveugle (cécité et obscurité) les joueurs proches ; un joueur aveuglé ne fige plus le boss : le boss se téléporte à deux blocs derrière chacun d'eux et plante un coup par combattant (`hits-per-player`, jamais plus de `max-hits`), chacun sur un joueur différent tant que c'est possible. Un craquement positionnel est le seul indice ; le coup tombe `strike-delay-ticks` après l'arrivée du boss. On esquive en s'éloignant au-delà de la portée du coup de l'endroit où le boss est arrivé, ou en retirant l'aveuglement (lait) : un joueur qui voit n'est plus pris pour cible.
- **Le nom disparaît pendant le Noir.** Même aveuglé, on verrait le nom du boss à travers le noir : il est retiré le temps de l'aveuglement et revient ensuite (`hide-name`).
- **Le Sursaut.** Le regard fige le boss, mais un boss frappé sur place sans répit finit par réagir : quand il est resté immobile `pinned-ticks` ticks en recevant des coups, il se téléporte derrière celui qui l'a le plus frappé et le frappe une fraction de seconde plus tard, comme au Noir (même esquive, avertissement dans la barre d'action de la cible). Rien ne compte pendant le Noir ni pendant une phase.
- **Joueurs en vol.** Une proie dont les `airborne-clearance` blocs sous les pieds sont vides (vol, élytres, grand saut) n'échappe ni au Noir ni au Sursaut : le boss la rejoint à sa hauteur, sans gravité, le temps de sa frappe, puis redescend au sol.
- **Dégâts réels.** Armure, protections et cœurs d'absorption arrêtent presque tout coup normal d'un équipement complet. La frappe du Noir, l'onde de choc et la mêlée retirent donc en plus une part des PV max du joueur, directement. Un coup mortel passe par un vrai coup (totem d'immortalité, règles de l'île).
- **Phases et cœurs.** Sous un seuil de PV, le boss devient invulnérable, la barre de boss change de titre et de couleur et affiche les cœurs restants. Des cœurs apparaissent sur des blocs d'air de l'île, à quelques blocs de lui ; chaque combattant au-delà du premier en ajoute `hearts-per-extra-player` (au plus le double des cœurs de la phase).
- **Briser un cœur.** Les cœurs sont des affichages lumineux (contour visible à travers les blocs) avec une zone de frappe, jamais de vrais blocs : on les frappe en clic gauche (maintenu ou répété), on ne peut pas les miner, et SuperiorSkyblock2 ne les compte pas dans le niveau de l'île. Un titre, un message, une barre d'action de progression à chaque coup, une colonne de particules au-dessus de chaque cœur et une boussole (direction et distance du cœur le plus proche, lisible même aveuglé) l'expliquent. Aucune protection d'île, explosion, piston ou créature ne les atteint.
- Quand le dernier cœur tombe, le boss redevient vulnérable. S'il meurt ou disparaît, tous les cœurs restants sont supprimés et l'aveuglement retiré ; les entités de cœur laissées par un arrêt brutal sont retirées au chargement de leur chunk.
- Le boss ne quitte jamais l'île et ne tombe jamais dans le vide : hors de l'île ou trop bas, il est ramené au centre.
- Pendant une phase, il ne frappe qu'en mêlée vanille et lance l'onde de choc plus souvent ; le Noir continue après un court répit.

### Le modèle 3D du Creaking

Le Creaking du boss est rendu invisible et remplacé par un modèle de 85 têtes de joueur (`models/creaking.txt` dans le jar), affiché par paquets avec packetevents, comme les compagnons de SHCompagnons : aucune entité de plus côté serveur, rien n'est envoyé aux joueurs qui ne voient pas le boss.

- **Affichage.** La première pièce est montée sur le Creaking côté client, les autres sur elle : le client les déplace avec le boss, aucun paquet de position. Quand le corps tourne de plus de 4°, une rotation par pièce (au plus tous les 3 ticks), lissée par le client. Au premier affichage chez un joueur, le modèle reste réduit à un point une demi-seconde, le temps que le client charge les textures des têtes.
- **Animations.** Images clés interpolées par le client : chaque pièce reçoit sa prochaine pose et la durée pour l'atteindre, une pièce immobile ne coûte rien. États de fond : `idle`, `walk` (il avance), `fige` (un joueur le regarde : immobile, avec des tics), `vol` (il flotte à la hauteur d'un joueur en l'air). Actions : `apparition` (il sort de terre à la fin du rituel, son IA attend la fin), `attaque` (coup de mêlée), `frappe` (coups du Noir et du Sursaut, impact calé sur `strike-delay-ticks`), `sursaut` (avertissement), `onde` et `resine` (lancées avant le coup pour que l'impact tombe au bon moment ; le combat n'est pas retardé), `rugissement` (début d'une phase), `degats`, `mort` (pendant la mise en scène de la mort, pose finale tenue ; le modèle rétrécit et disparaît au coup de grâce). Avec le modèle, la mise en scène de la mort ne secoue plus l'entité (tremblement, élévation, rotation) : les particules et les sons restent.
- **Nom.** Le client ne montre pas le nom d'une entité invisible qui porte des passagers : le modèle affiche lui-même le nom du boss au-dessus de sa tête (texte face au joueur), caché pendant le Noir comme avant.
- **Taille.** `model.scale` (1 = 3,4 blocs de haut) et `model.entity-scale`, l'attribut de taille de l'entité : hitbox et portée suivent le modèle, les coups touchent là où on le voit.
- **Coût mesuré par joueur qui regarde le boss** : 64 paquets par seconde au repos, environ 280 en marche, 330 en moyenne en combat, pointes vers 1 000 pendant une action. Le fichier est lu en arrière-plan au démarrage (une centaine de millisecondes).
- Sans packetevents, avec `model.enabled: false` ou si le fichier est illisible, le Creaking vanilla reste visible. Un fichier `plugins/SHEggDragonBoss/models/creaking.txt` remplace celui du jar.

## Dépendances

- Obligatoires : `SuperiorSkyblock2`, `FancyHolograms`
- Optionnelles : `SHLogs`, `packetevents` (modèle 3D du Creaking)

## Commandes

Alias de `/sheggdragonboss` : `/shboss`. Sans argument, la commande affiche les sous-commandes que l'expéditeur a le droit d'utiliser ; elle marche depuis la console.

| Commande | Description | Permission |
|---|---|---|
| `/sheggdragonboss reload` | Recharge `config.yml` et `messages.yml` (les boss en cours appliquent les nouveaux réglages au tick suivant). Si un fichier est invalide, l'ancienne configuration reste active et la réponse donne le fichier et l'erreur. Ne touche ni `hatching-data.yml` ni `dormant-bosses.yml`. | `sheggdragonboss.admin.reload` |
| `/sheggdragonboss give <joueur> <coeur\|briques> [quantité]` | Donne le cœur ou les briques de résine du rituel du Creaking, avec le lore qui explique leur usage. Utilisable depuis la console par les boîtes, le temps de jeu, le VoteParty ou le boss de l'œuf. | `sheggdragonboss.admin.give` |
| `/sheggdragonboss list` | Boss en vie ou dormants (propriétaire de l'île, position, PV, phase) et rituels en cours (objets donnés ou compte à rebours). | `sheggdragonboss.admin.list` |
| `/sheggdragonboss summon <joueur> <creaking\|dragon> [commande]` | Fait apparaître le boss à la position du joueur, comme à la fin d'un rituel (mêmes PV, phases et récompenses au tueur seul, journal des boss). Le joueur doit être en ligne, dans le monde des îles, sur une île dont il est membre, sans boss (en vie ou dormant) ni rituel en compte à rebours, sur un sol plein et dégagé (le boss est posé sous ses pieds ou à moins de `summon.vertical-search` blocs, puis le `spawn-offset-y` du rituel s'ajoute) ; le rituel du boss doit être activé. Refusé : message au joueur et à l'expéditeur, puis `[commande]` est exécutée par la console, `{player}` remplacé par le joueur (la boutique s'en sert pour rendre l'objet payé). | `sheggdragonboss.admin.summon` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `sheggdragonboss.admin` | Toutes les commandes d'administration. | `op` |
| `sheggdragonboss.admin.reload` | `/sheggdragonboss reload`. | `op` |
| `sheggdragonboss.admin.give` | `/sheggdragonboss give`. | `op` |
| `sheggdragonboss.admin.list` | `/sheggdragonboss list`. | `op` |
| `sheggdragonboss.admin.summon` | `/sheggdragonboss summon`. | `op` |

Le rituel lui-même demande le privilège d'île `EGG_HATCH` (SuperiorSkyblock2).

## Configuration

Deux fichiers, documentés clé par clé :

- `config.yml` : rituels (bloc, objet, quantité, animation), sons, réglages de combat des boss, récompenses, privilège d'île. Aucun texte.
- `messages.yml` : tous les textes (messages de chat, titres, barres d'action, noms et barres de boss, hologrammes, lores des objets, commande, journal des boss). Couleurs `&` et `&#RRGGBB`, placeholders `%comme-ceci%`.

Les deux sont lus au démarrage et par `/sheggdragonboss reload` (le nom du privilège d'île et la section `reseau` ne le sont qu'au démarrage) ; une valeur ou une clé invalide produit un avertissement dans la console et la valeur livrée est utilisée. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHEggDragonBoss/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

Données écrites par le plugin dans le même dossier : `hatching-data.yml` (rituels en cours) et `dormant-bosses.yml` (boss endormis). Dans les deux, un champ `type` (`dragon` ou `creaking`) est facultatif : son absence signifie `dragon`.

Le journal des boss est écrit dans le fichier `boss` du dossier `SHEggDragonBoss` de SHLogs (`plugins/SHLogs/`), avec les lignes de la section `journal` de `messages.yml`. Sans SHLogs, elles passent dans la console.

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
