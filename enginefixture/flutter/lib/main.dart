import 'dart:convert';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// Owned fixture: reads the packaged bytes through Flutter's real AssetBundle.
// No test-only override path, remote settings, or ModKit runtime API.
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final config = jsonDecode(await rootBundle.loadString('assets/game.json'));
  runApp(MaterialApp(home: Game(config: config as Map<String, dynamic>)));
}

class Game extends StatefulWidget {
  const Game({super.key, required this.config});
  final Map<String, dynamic> config;
  @override
  State<Game> createState() => _GameState();
}

class _GameState extends State<Game> {
  late int health = widget.config['health'] as int;
  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
      Semantics(label: 'Health: $health', excludeSemantics: true,
        child: Text('Health: $health', style: const TextStyle(fontSize: 28))),
      Semantics(label: 'Damage: ${widget.config['damage']}', excludeSemantics: true,
        child: Text('Damage: ${widget.config['damage']}')),
      Semantics(label: health == 0 ? 'GAME OVER' : 'ALIVE', excludeSemantics: true,
        child: Text(health == 0 ? 'GAME OVER' : 'ALIVE')),
      ElevatedButton(onPressed: () => setState(() {
        health = max(0, health - (widget.config['damage'] as int));
      }), child: const Text('Take damage')),
    ])),
  );
}
