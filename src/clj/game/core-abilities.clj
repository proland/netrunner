(in-ns 'game.core)
(declare corp-trace-prompt optional-ability psi-game resolve-psi resolve-trace show-select)

; Functions for implementing card abilities and prompts

; Abilities
(defn resolve-ability
  "Resolves an ability defined by the given ability map. Checks :req functions; shows prompts, psi games,
  traces, etc. as needed; charges costs; invokes :effect functions. All card effects and most engine effects
  should run through this method.

  All keys that can be used in an ability map:
  COMMON KEYS
  :req -- a 4-argument function that must return true or false. If false, the ability will not be resolved.
  :cost -- a vector of costs to charge, for example [:credit 1 :click 1]. If the costs cannot be paid, the ability
           will not be resolved.
  :msg -- either a string or a 4-argument function that returns a string. Prints to the log when the ability is finished.
  :effect -- a 4-argument function that will be called if the ability will be resolved (if :req is true or not present,
             costs can be paid, and any prompts are resolved.)
  :player -- manually specifies which player the ability should affect, rather than leave it implicit.

  PROMPT KEYS
  :choices -- this key signals a prompt of some kind. Can be:
      * a 4-argument function returning a vector of cards or strings -- user chooses one option.
        Called a 'choice' prompt.
      * the keyword :credit -- user chooses an integer up to their current credit amount.
      * the keyword :counter -- user chooses an integer up to the :counter value of the given card.
      * a map containing the keyword :number with a value of a 4-argument function returning an integer -- user
        chooses an integer up to the value of the map.
      * a map containing the keyword :req with a value of a 1-argument function returning true or false. Triggers a
        'select' prompt with targeting cursor; only cards that cause the 1-argument function to return true will
        be allowed.
  :prompt -- a string or 4-argument function returning a string to display in the prompt menu.
  :priority -- true if the prompt should be added at the front of the prompt queue. Defaults to false.
  :not-distinct -- true if the prompt should not collapse :choices entries of the same string to one button.
                   Defaults to false.
  :cancel-effect -- if the prompt uses a Cancel button, this 4-argument function will be called if the user
                    chooses Cancel.

  OTHER PROMPTS
  :psi -- a map that starts a psi game. Has keys :equal and :not-equal, whose values are 4-argument functions
          which are triggered when the game resolves.
  :trace -- a map that starts a trace. Has a :base key that is either an integer or a 4-argument function
            returning an integer for the base strength. The map is otherwise a normal ability map that can
            contain :req, :effect, or any other key in an ability; the :effect is only triggered if the trace
            succeeds. Can also have a key :kicker that is an ability map with key :min, whose effect triggers
            if the trace strength matches or exceeds the :min value. (Constellation ice.)

  OTHER KEYS
  :counter-cost / :advance-counter-cost -- number of counters to remove to resolve the ability
  :once -- its only value is :per-turn; signifies an effect that can only be triggered once per turn.
  :once-key -- by default, each :once is distinct per card. If multiple copies of a card can only resolve
               some ability once between all of them, then the card should specify a manual :once-key that can
               be any value, preferrably a unique keyword.
  :optional -- shows a 'Yes/No' prompt to let the user decide whether to resolve the ability.
  :end-turn -- if the ability is resolved, then this ability map will be resolved at the end of the turn."

  ; perhaps the most important function in the game logic
  [state side {:keys [counter-cost advance-counter-cost cost effect msg req once
                      once-key optional prompt choices end-turn player psi trace
                      not-distinct priority cancel-effect] :as ability}
   {:keys [title cid counter advance-counter] :as card} targets]
  (when ability
    ; Is this an optional ability?
    (when (and optional
               (not (get-in @state [(:once optional) (or (:once-key optional) cid)]))
               (or (not (:req optional)) ((:req optional) state side card targets)))
      ; if the ability has an :optional key, and the :req for the optional returns true or is not present.
      (optional-ability state (or (:player optional) side) card (:prompt optional) optional targets))
    ; Is this a psi game?
    (when (and psi (or (not (:req psi)) ((:req psi) state side card targets)))
      (psi-game state side card psi))
    ; Is this a trace?
    (when (and trace (or (not (:req trace)) ((:req trace) state side card targets)))
      (corp-trace-prompt state card trace))
    ; Ensure this ability can be triggered more than once per turn, or has not been yet been triggered this turn.
    (when (and (not (get-in @state [once (or once-key cid)]))
               (or (not req) (req state side card targets)))
      (if choices
        ; It's a prompt!
        (if (map? choices)
          ; Two types of choices use maps: select prompts, and :number prompts.
          (if (:req choices)
            ; a select prompt
            (show-select state (or player side) card ability {:priority priority})
            ; a :number prompt
            (let [n ((:number choices) state side card targets)]
              (prompt! state (or player side) card prompt {:number n} (dissoc ability :choices)
                       {:priority priority :cancel-effect cancel-effect})))
          ; Not a map; either :credit, :counter, or a vector of cards or strings.
          (let [cs (if-not (fn? choices)
                     choices ; :credit or :counter
                     (let [cards (choices state side card targets)] ; a vector of cards or strings
                       (if not-distinct
                         cards (distinct-by :title cards))))]
            (prompt! state (or player side) card prompt cs (dissoc ability :choices)
                     {:priority priority :cancel-effect cancel-effect})))
        ; Not a prompt. Ensure that any counters required can be paid.
        (when (and (or (not counter-cost) (<= counter-cost (or counter 0)))
                   (or (not advance-counter-cost) (<= advance-counter-cost (or advance-counter 0))))
          ; Ensure that any costs can be paid.
          (when-let [cost-str (apply pay (concat [state side card] cost))]
            (let [c (-> card
                        (update-in [:advance-counter] #(- (or % 0) (or advance-counter-cost 0)))
                        (update-in [:counter] #(- (or % 0) (or counter-cost 0))))]
              ; Remove any counters.
              (when (or counter-cost advance-counter-cost)
                (update! state side c)
                (when (= (:type card) "Agenda") (trigger-event state side :agenda-counter-spent card)))
              ; Print the message.
              (when msg
                (let [desc (if (string? msg) msg (msg state side card targets))]
                  (system-msg state (to-keyword (:side card))
                              (str (build-spend-msg cost-str "use")
                                   title (when desc (str " to " desc))))))
              ; Trigger the effect.
              (when effect (effect state side c targets))
              ; Record the :end-turn effect.
              (when end-turn
                (swap! state update-in [side :register :end-turn]
                       #(conj % {:ability end-turn :card card :targets targets}))))
            ; Record the :once.
            (when once (swap! state assoc-in [once (or once-key cid)] true))))))))

(defn optional-ability
  "Shows a 'Yes/No' prompt and resolves the given ability if Yes is chosen."
  [state side card msg ability targets]
  (show-prompt state side card msg ["Yes" "No"] #(if (= % "Yes")
                                                  (when-let [yes-ability (:yes-ability ability)]
                                                    (resolve-ability state side yes-ability card targets))
                                                  (when-let [no-ability (:no-ability ability)]
                                                    (resolve-ability state side no-ability card targets)))))


; Prompts
(defn prompt!
  "Shows a prompt with the given message and choices. The given ability will be resolved
  when the user resolves the prompt. Cards should generally not call this function directly; they
  should use resolve-ability to resolve a map containing prompt data.

  choices can be:
  1. a vector of strings -- shows one prompt button for each string; the selected option is passed to the
        ability as target.
  2. a vector of cards -- shows one button for each card's title; the selected card is passed as target.
  3. the keyword :credit -- shows a numeric selection box with a max value equal to the player's credits.
  4. the keyword :counter -- shows a numeric selection box with a max value equal to the :counter of the card.
  5. a map with keyword :number -- shows a numeric selection box with max value equal to the :number of the map."
  ([state side card msg choices ability] (prompt! state side card msg choices ability nil))
  ([state side card msg choices ability {:keys [priority prompt-type cancel-effect] :as args}]
   (show-prompt state side card msg choices #(resolve-ability state side ability card [%])
                (if cancel-effect (assoc args :cancel-effect #(cancel-effect state side card [%]))
                                  args))))

(defn show-prompt
  "Engine-private method for displaying a prompt where a *function*, not a card ability, is invoked
  when the prompt is resolved. All prompts flow through this method."
  ([state side card msg choices f] (show-prompt state side card msg choices f nil))
  ([state side card msg choices f {:keys [priority prompt-type show-discard cancel-effect] :as args}]
   (let [prompt (if (string? msg) msg (msg state side card nil))]
     (when (or (:number choices) (#{:credit :counter} choices) (> (count choices) 0))
       (swap! state update-in [side :prompt]
              (if priority
                #(cons {:msg prompt :choices choices :effect f :card card
                        :prompt-type prompt-type :show-discard show-discard :cancel-effect cancel-effect} (vec %))
                #(conj (vec %) {:msg prompt :choices choices :effect f :card card
                                :prompt-type prompt-type :show-discard show-discard :cancel-effect cancel-effect})))))))

(defn show-select
  "A select prompt uses a targeting cursor so the user can click their desired target of the ability.
  As with prompt!, the preferred method for showing a select prompt is through resolve-ability."
  ([state side card ability] (show-select state side card ability nil))
  ([state side card ability {:keys [priority] :as args}]
   ; if :max is a function, call it and assoc its return value as the new :max number of cards
   ; that can be selected.
   (let [ability (update-in ability [:choices :max] #(if (fn? %) (% state side card nil) %))]
     (swap! state update-in [side :selected]
            #(conj (vec %) {:ability (dissoc ability :choices) :req (get-in ability [:choices :req])
                            :max (get-in ability [:choices :max])}))
     (show-prompt state side card
                  (if-let [msg (:prompt ability)]
                    msg
                    (if-let [m (get-in ability [:choices :max])]
                      (str "Select up to " m " targets for " (:title card))
                      (str "Select a target for " (:title card))))
                  ["Done"] (fn [choice] (resolve-select state side))
                  (assoc args :prompt-type :select :show-discard (:show-discard ability))))))

(defn resolve-select
  "Resolves a selection prompt by invoking the prompt's ability with the targeted cards.
  Called when the user clicks 'Done' or selects the :max number of cards."
  [state side]
  (let [selected (get-in @state [side :selected 0])
        cards (map #(dissoc % :selected) (:cards selected))
        curprompt (first (get-in @state [side :prompt]))]
    (when-not (empty? cards)
      (doseq [card cards]
        (update! state side card))
      (resolve-ability state side (:ability selected) (:card curprompt) cards))
    (swap! state update-in [side :selected] #(vec (rest %)))
    (swap! state update-in [side :prompt] (fn [pr] (filter #(not= % curprompt) pr)))))


; Psi games
(defn psi-game
  "Starts a psi game by showing the psi prompt to both players. psi is a map containing
  :equal and :not-equal abilities which will be triggered in resolve-psi accordingly."
  [state side card psi]
  (swap! state assoc :psi {})
  (doseq [s [:corp :runner]]
    (show-prompt state s card (str "Choose an amount to spend for " (:title card))
                 (map #(str % " [Credits]") (range (min 3 (inc (get-in @state [s :credit])))))
                 #(resolve-psi state s card psi (Integer/parseInt (first (split % #" ")))))))

(defn resolve-psi
  "Resolves a psi game by charging credits to both sides and invoking the appropriate
  resolution ability."
  [state side card psi bet]
  (swap! state assoc-in [:psi side] bet)
  (let [opponent (if (= side :corp) :runner :corp)]
    (when-let [opponent-bet (get-in @state [:psi opponent])]
      (lose state opponent :credit opponent-bet)
      (system-msg state opponent (str "spends " opponent-bet " [Credits]"))
      (lose state side :credit bet)
      (system-msg state side (str "spends " bet " [Credits]"))
      (trigger-event state side :psi-game nil)
      (when-let [ability (if (= bet opponent-bet) (:equal psi) (:not-equal psi))]
        (resolve-ability state (:side card) ability card nil)))))


; Traces
(defn init-trace
  "Shows a trace prompt to the runner, after the corp has already spent credits to boost."
  [state side card {:keys [base] :as ability} boost]
  (trigger-event state side :pre-init-trace card)
  (let [bonus (or (get-in @state [:bonus :trace]) 0)
        base (if (fn? base) (base state side card nil) base)
        total (+ base boost bonus)]
    (system-msg state :corp (str "uses " (:title card)
                                 " to initiate a trace with strength " total
                                 " (" base
                                 (when (> bonus 0) (str " + " bonus " bonus"))
                                 " + " boost " [Credits]) (" (:msg ability) ")"))
    (swap! state update-in [:bonus] dissoc :trace)
    (show-prompt state :runner card (str "Boost link strength?") :credit #(resolve-trace state side %))
    (swap! state assoc :trace {:strength total :ability ability :card card})
    (trigger-event state side :trace nil)))

(defn resolve-trace
  "Compares trace strength and link strength and triggers the appropriate effects."
  [state side boost]
  (let [runner (:runner @state)
        {:keys [strength ability card]} (:trace @state)]
    (system-msg state :runner (str " spends " boost " [Credits] to increase link strength to "
                                   (+ (:link runner) boost)))
    (let [succesful (> strength (+ (:link runner) boost))
          ability (if succesful ability (:unsuccessful ability))]
      (resolve-ability state :corp ability card [strength (+ (:link runner) boost)])
      (trigger-event state :corp (if succesful :successful-trace :unsuccessful-trace)))
    (when-let [kicker (:kicker ability)]
      (when (>= strength (:min kicker))
        (resolve-ability state :corp kicker card [strength (+ (:link runner) boost)])))))

(defn corp-trace-prompt
  "Starts the trace process by showing the boost prompt to the corp."
  [state card trace]
  (show-prompt state :corp card "Boost trace strength?" :credit
               #(init-trace state :corp card trace %)))