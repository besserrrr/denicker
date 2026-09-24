BUILD (needs JDK 8 and a ForgeGradle 2.1 1.8.9 workspace):
  1. Download Forge 1.8.9 MDK (11.15.1.2318), copy its gradlew + gradle/ folder into this folder.
  2. Use Gradle 2.14 (the MDK wrapper does).
  3. Run:  gradlew setupDecompWorkspace   then   gradlew build
  4. Jar is in build/libs/Denicker-1.0.jar -> put in .minecraft/mods

USE (in game, client side only):
  /denick        list names found in team/score data that are missing from tab
  /denick uuid   check tab UUIDs against Mojang, prints nick -> real name
  /denick dump   write raw tab/team/score data to latest.log (find where Pika leaks)
  /denick clear  reset
