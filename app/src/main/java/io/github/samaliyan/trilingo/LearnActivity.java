package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Learn: the learning plan. The home of it shows today's lesson, the level and its units (a path of
 * eight words each), the next grammar lesson of the level, and the learner's weak spots. A lesson runs one
 * exercise at a time: new words are introduced and practised; due and weak words are asked the way they
 * need (meaning, writing, article, verb form, saying); a wrong answer comes back at the end. At the end:
 * the result, the words to practise again, and what comes tomorrow. Everything is kept ({@link Course}).
 */
public final class LearnActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int SPEECH = 91;

    private Ui ui;
    private Page page;
    private Store store;
    private Notebook nb;
    private Course course;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final Random random = new Random();
    /** The level's words (course order), per language and level. */
    private static final Map<String, List<Course.Entry>> ENTRIES = new HashMap<>();

    // the lesson running
    private List<Course.Exercise> lesson;
    private int at, right, answeredCount;
    private boolean answered, noSpeaking;
    private final Set<String> movedOn = new HashSet<>();
    private final List<Course.WordState> newWords = new ArrayList<>(), troubles = new ArrayList<>();
    private TextView feedback, go;
    private EditText field;
    private ProgressBar bar;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        store = new Store(this);
        nb = Store.notebook(this);
        course = Store.course(this);
        page = new Page(this, ui, getString(R.string.learn));
        try {
            tts = new TextToSpeech(getApplicationContext(), this);
        } catch (RuntimeException e) {
            tts = null;
        }
        loading();
        Lexicon.load(this, () -> runOnUiThread(() -> {
            if (!isDestroyed()) home();
        }));
    }

    @Override public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
    }

    @Override protected void onResume() {
        super.onResume();
        if (lesson == null && Lexicon.dict(Lang.DE) != null) home();
    }

    @Override protected void onPause() {
        super.onPause();
        course.save();
        WidgetProvider.updateAll(this);
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // gone
            }
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (lesson != null) {
            new AlertDialog.Builder(this).setMessage(R.string.learn_quit_question)
                    .setPositiveButton(R.string.learn_quit, (d, w) -> {
                        lesson = null;
                        course.save();
                        home();
                    })
                    .setNegativeButton(R.string.learn_keep_going, null).show();
            return;
        }
        super.onBackPressed();
    }

    private void loading() {
        page.content.removeAllViews();
        TextView wait = ui.text(15, ui.sub, Ui.REGULAR);
        wait.setText(R.string.loading);
        page.content.addView(wait);
    }

    private Lang lang() {
        return course.learning;
    }

    private String level() {
        Course.Track t = course.track(lang());
        if (t.day < 0 && course.introduced(lang(), new ArrayList<>()) == 0) t.level = store.myLevel(lang());
        return t.level;
    }

    private List<Course.Entry> entries() {
        String k = lang().code + level();
        List<Course.Entry> e = ENTRIES.get(k);
        if (e == null) {
            e = Course.entries(lang(), level(), Lexicon.levels(lang()), Lexicon.words(lang()), Lexicon.dict(lang()),
                    Lexicon.nouns(), Lexicon::mainlyVerb);
            ENTRIES.put(k, e);
        }
        return e;
    }

    // ------------------------------------------------------------------ the plan

    private void home() {
        lesson = null;
        page.title.setText(R.string.learn);
        LinearLayout c = page.content;
        c.removeAllViews();
        long now = System.currentTimeMillis();
        course.newDay(lang(), now);
        List<Course.Entry> all = entries();
        List<List<Course.Entry>> units = Course.units(all);
        Course.Track t = course.track(lang());

        // language
        LinearLayout langs = new LinearLayout(this);
        for (Lang l : new Lang[]{Lang.DE, Lang.EN}) {
            boolean on = l == lang();
            TextView chip = ui.line(15, on ? ui.onAccent : ui.text, Ui.MEDIUM);
            chip.setText(l.label);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(ui.dp(44));
            chip.setPadding(ui.dp(18), 0, ui.dp(18), 0);
            chip.setBackground(on ? ui.ripple(ui.round(ui.accent, 22), 22) : ui.ripple(ui.outline(ui.bg, ui.line, 22), 22));
            chip.setContentDescription(getString(R.string.learn_language_desc, l.label));
            chip.setSelected(on);
            chip.setOnClickListener(v -> {
                course.learning = l;
                course.save();
                home();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            langs.addView(chip, lp);
        }
        c.addView(langs, Page.full(ui.dp(4), ui.dp(14)));

        // today
        List<Course.WordState> due = due(now);
        int cur = course.currentUnit(lang(), units);
        int newLeft = 0;
        if (!units.isEmpty()) for (Course.Entry e : units.get(cur)) if (course.state(lang(), e.word) == null) newLeft++;
        newLeft = Math.min(newLeft, Math.max(0, t.dailyNew - t.newToday));
        int weak = Math.min(3, course.weak(lang()).size());
        LinearLayout today = box(ui.accentSoft);
        TextView th = ui.line(13, ui.accent, Ui.MEDIUM);
        th.setText(t.lessonsToday > 0 ? R.string.learn_today_done : R.string.learn_today);
        today.addView(th);
        TextView tt = ui.text(22, ui.text, Ui.MEDIUM);
        tt.setText(t.lessonsToday > 0 ? getString(R.string.learn_today_done_title) : getString(R.string.learn_today_title,
                level(), cur + 1));
        today.addView(tt, Page.full(ui.dp(4), 0));
        TextView plan = ui.text(14, ui.sub, Ui.REGULAR);
        plan.setText(getString(R.string.learn_today_plan, newLeft, Math.min(6, due.size()), weak));
        plan.setLineSpacing(0, 1.25f);
        today.addView(plan, Page.full(ui.dp(4), ui.dp(12)));
        boolean any = newLeft + due.size() + weak > 0;
        TextView start = ui.pill(getString(t.lessonsToday > 0 ? R.string.learn_more : R.string.learn_start), true);
        start.setOnClickListener(v -> {
            if (units.isEmpty()) return;
            List<Course.Exercise> ex = course.lesson(lang(), units.get(cur), due, all, System.currentTimeMillis(), random);
            if (ex.isEmpty()) {
                Toast.makeText(this, R.string.learn_nothing_now, Toast.LENGTH_LONG).show();
                return;
            }
            run(ex, getString(R.string.learn_today));
        });
        start.setEnabled(any || t.lessonsToday == 0);
        today.addView(start, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(50)));
        int streak = nb.progress.streak(now);
        TextView st = ui.text(13, ui.sub, Ui.REGULAR);
        st.setText(getResources().getQuantityString(R.plurals.streak_days, streak, streak)
                + " · " + getString(R.string.learn_new_per_day, t.dailyNew));
        today.addView(st, Page.full(ui.dp(10), 0));
        c.addView(today, Page.full(0, ui.dp(14)));

        // level
        int met = course.introduced(lang(), all);
        LinearLayout lv = new LinearLayout(this);
        lv.setGravity(Gravity.CENTER_VERTICAL);
        TextView lvt = ui.line(15, ui.text, Ui.MEDIUM);
        lvt.setText(getString(R.string.learn_level, lang().label, level(), Levels.name(level())));
        lv.addView(lvt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView change = ui.pill(getString(R.string.learn_change_level), false);
        change.setOnClickListener(v -> chooseLevel());
        lv.addView(change, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        c.addView(lv, Page.full(ui.dp(4), ui.dp(6)));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(Math.max(1, all.size()));
        pb.setProgress(met);
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        pb.setContentDescription(getString(R.string.learn_level_progress, met, all.size()));
        c.addView(pb, Page.full(0, 0));
        TextView lvp = ui.text(13, ui.sub, Ui.REGULAR);
        lvp.setText(getString(R.string.learn_level_progress, met, all.size()));
        c.addView(lvp, Page.full(ui.dp(2), ui.dp(14)));

        // the path: units around the current one
        int from = Math.max(0, cur - 2), to = Math.min(units.size(), cur + 4);
        if (from > 0) c.addView(smallNote(getString(R.string.learn_units_done, from)), Page.full(0, ui.dp(8)));
        for (int i = from; i < to; i++) c.addView(unitRow(units, i, cur, due, all), Page.full(0, ui.dp(8)));
        if (to < units.size()) c.addView(smallNote(getString(R.string.learn_units_more, units.size() - to)), Page.full(0, ui.dp(14)));

        // grammar of the level
        Grammar.Lesson g = nextGrammar();
        if (g != null) {
            TextView gh = ui.line(13, ui.accent, Ui.MEDIUM);
            gh.setText(R.string.learn_grammar);
            c.addView(gh, Page.full(ui.dp(10), ui.dp(6)));
            LinearLayout gr = row(Icons.Kind.BOOK, g.title(store.explainLang()), getString(R.string.learn_grammar_note, g.level));
            gr.setOnClickListener(v -> startActivity(new Intent(this, LessonActivity.class).putExtra(LessonActivity.EXTRA_ID, g.id)));
            c.addView(gr, Page.full(0, ui.dp(10)));
        }

        // weak spots
        TextView wh = ui.line(13, ui.accent, Ui.MEDIUM);
        wh.setText(R.string.learn_weak_spots);
        c.addView(wh, Page.full(ui.dp(10), ui.dp(6)));
        List<Course.WordState> weakList = course.weak(lang());
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(3, weakList.size()); i++) {
            Course.WordState s = weakList.get(i);
            if (names.length() > 0) names.append(" · ");
            names.append(s.display).append(" (").append(skillName(s.weakest())).append(')');
        }
        LinearLayout wr = row(Icons.Kind.CARDS, getString(R.string.learn_weak_words, weakList.size()),
                weakList.isEmpty() ? getString(R.string.learn_weak_none) : names.toString());
        wr.setOnClickListener(v -> drill(-1));
        c.addView(wr, Page.full(0, ui.dp(8)));
        if (lang() == Lang.DE) {
            LinearLayout ar = row(Icons.Kind.TABLE, getString(R.string.practice_article), getString(R.string.learn_drill_articles));
            ar.setOnClickListener(v -> drill(Course.ARTICLE));
            c.addView(ar, Page.full(0, ui.dp(8)));
        }
        LinearLayout fr = row(Icons.Kind.PEN, getString(R.string.practice_verbs), getString(R.string.learn_drill_verbs));
        fr.setOnClickListener(v -> drill(Course.FORM));
        c.addView(fr, Page.full(0, ui.dp(8)));
        LinearLayout sr = row(Icons.Kind.MIC, getString(R.string.practice_say), getString(R.string.learn_drill_speaking));
        sr.setOnClickListener(v -> drill(Course.SPEAKING));
        c.addView(sr, Page.full(0, ui.dp(8)));
        LinearLayout pr = row(Icons.Kind.CHART, getString(R.string.progress), getString(R.string.learn_progress_note));
        pr.setOnClickListener(v -> startActivity(new Intent(this, ProgressActivity.class)));
        c.addView(pr, Page.full(0, ui.dp(8)));

        // reminder
        int rem = store.reminder();
        LinearLayout rr = row(Icons.Kind.BELL, getString(R.string.learn_reminder),
                rem < 0 ? getString(R.string.learn_reminder_off) : getString(R.string.learn_reminder_at, rem / 60, rem % 60));
        rr.setOnClickListener(v -> reminder());
        c.addView(rr, Page.full(0, ui.dp(20)));
    }

    private TextView smallNote(String s) {
        TextView t = ui.text(13, ui.sub, Ui.REGULAR);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout box(int color) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(ui.round(color, 20));
        b.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        return b;
    }

    private LinearLayout row(Icons.Kind icon, String title, String note) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(ui.ripple(ui.round(ui.surface, 18), 18));
        r.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        r.setMinimumHeight(ui.dp(60));
        ImageView i = new ImageView(this);
        i.setImageDrawable(Icons.of(icon, ui.accent));
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(i, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = ui.line(16, ui.text, Ui.MEDIUM);
        t.setText(title);
        texts.addView(t);
        TextView n = ui.text(13, ui.sub, Ui.REGULAR);
        n.setText(note);
        Ui.bidi(n);
        texts.addView(n);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = ui.dp(14);
        r.addView(texts, tp);
        r.setContentDescription(title + ". " + note);
        return r;
    }

    private View unitRow(List<List<Course.Entry>> units, int i, int cur, List<Course.WordState> due, List<Course.Entry> all) {
        List<Course.Entry> u = units.get(i);
        int done = course.unitDone(lang(), u);
        boolean finished = done == u.size(), current = i == cur, open = finished || current;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(ui.ripple(current ? ui.outline(ui.surface, ui.accent, 18) : ui.round(ui.surface, 18), 18));
        r.setPadding(ui.dp(14), ui.dp(12), ui.dp(16), ui.dp(12));
        r.setAlpha(open ? 1f : 0.55f);
        TextView num = ui.line(16, finished || current ? ui.onAccent : ui.sub, Ui.MEDIUM);
        num.setText(finished ? "✓" : String.valueOf(i + 1));
        num.setGravity(Gravity.CENTER);
        num.setBackground(ui.round(finished || current ? ui.accent : ui.line, 20));
        num.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(num, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = ui.line(16, ui.text, Ui.MEDIUM);
        t.setText(getString(R.string.learn_unit, i + 1));
        texts.addView(t);
        StringBuilder w = new StringBuilder();
        for (int k = 0; k < Math.min(4, u.size()); k++) w.append(k > 0 ? ", " : "").append(u.get(k).display);
        if (u.size() > 4) w.append(" …");
        TextView n = ui.line(13, ui.sub, Ui.REGULAR);
        n.setText(w);
        texts.addView(n);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = ui.dp(14);
        r.addView(texts, tp);
        TextView count = ui.line(13, current ? ui.accent : ui.sub, Ui.MEDIUM);
        count.setText(getString(R.string.learn_unit_count, done, u.size()));
        r.addView(count);
        r.setContentDescription(getString(R.string.learn_unit, i + 1) + ". " + w + ". "
                + getString(R.string.learn_unit_count, done, u.size()) + (open ? "" : ". " + getString(R.string.learn_unit_locked_short)));
        r.setOnClickListener(v -> {
            if (!open) {
                Toast.makeText(this, getString(R.string.learn_unit_locked, cur + 1), Toast.LENGTH_SHORT).show();
                return;
            }
            // a unit on its own: its new words (as many as today allows) and its words practised
            List<Course.Exercise> ex = new ArrayList<>(course.lesson(lang(), u, new ArrayList<>(), all, System.currentTimeMillis(), random));
            ex.removeIf(e -> !inUnit(e.word, u));
            for (Course.Entry e : u) {
                Course.WordState s = course.state(lang(), e.word);
                if (s != null && ex.size() < 14) ex.add(course.practise(s, all, random));
            }
            if (ex.isEmpty()) {
                Toast.makeText(this, R.string.learn_nothing_now, Toast.LENGTH_LONG).show();
                return;
            }
            run(ex, getString(R.string.learn_unit, i + 1));
        });
        return r;
    }

    private static boolean inUnit(Course.WordState s, List<Course.Entry> u) {
        for (Course.Entry e : u) if (e.word.equalsIgnoreCase(s.word)) return true;
        return false;
    }

    /** Due saved words of this language (the course's and the learner's own), as course words. */
    private List<Course.WordState> due(long now) {
        List<Course.WordState> out = new ArrayList<>();
        Dict d = Lexicon.dict(lang());
        for (Notebook.Card card : nb.due(null, null, now, 100)) {
            if (card.lang != lang()) continue;
            Course.WordState s = course.state(lang(), card.word);
            if (s == null) {
                Dict.Entry e = d == null ? null : d.find(card.word);
                Nouns.Info n = lang() == Lang.DE && Lexicon.nouns() != null && Character.isUpperCase(card.word.charAt(0))
                        ? Lexicon.nouns().exact(card.word) : null;
                boolean noun = n != null && n.genders.size() == 1;
                boolean verb = !noun && "verb".equals(Course.firstPos(e)) && Lexicon.mainlyVerb(lang(), card.word);
                s = course.adopt(lang(), card.word, noun ? n.articles() + " " + n.noun : card.word, card.back, noun, verb);
            }
            out.add(s);
        }
        return out;
    }

    private void drill(int skill) {
        List<Course.Exercise> ex = course.drill(lang(), skill, due(System.currentTimeMillis()), entries(), random, 10);
        if (ex.isEmpty()) {
            Toast.makeText(this, skill == Course.ARTICLE ? R.string.practice_no_nouns : skill == Course.FORM
                    ? R.string.practice_no_verbs : skill < 0 ? R.string.learn_weak_none : R.string.learn_nothing_now, Toast.LENGTH_LONG).show();
            return;
        }
        run(ex, skill < 0 ? getString(R.string.learn_weak_words, course.weak(lang()).size()) : getString(R.string.learn_title_drill));
    }

    private Grammar.Lesson nextGrammar() {
        Grammar g = Lexicon.grammar();
        if (g == null) return null;
        Set<String> read = store.readLessons();
        for (Grammar.Lesson l : g.of(lang())) if (l.level.equals(level()) && !read.contains(l.id)) return l;
        return null;
    }

    private void chooseLevel() {
        String[] levels = {"A1", "A2", "B1", "B2", "C1"};
        String[] names = new String[levels.length];
        for (int i = 0; i < levels.length; i++) names[i] = levels[i] + " · " + Levels.name(levels[i]);
        new AlertDialog.Builder(this).setTitle(R.string.learn_change_level)
                .setItems(names, (d, w) -> {
                    course.track(lang()).level = levels[w];
                    store.setMyLevel(lang(), levels[w]);
                    course.save();
                    home();
                }).show();
    }

    private void reminder() {
        int rem = store.reminder();
        String[] items = {getString(R.string.learn_reminder_pick), getString(R.string.learn_reminder_turn_off)};
        new AlertDialog.Builder(this).setTitle(R.string.learn_reminder).setItems(rem < 0 ? new String[]{items[0]} : items, (d, w) -> {
            if (w == 1) {
                store.setReminder(-1);
                Reminder.schedule(this);
                home();
                return;
            }
            int start = rem < 0 ? 20 * 60 : rem;
            new TimePickerDialog(this, (tp, h, m) -> {
                store.setReminder(h * 60 + m);
                Reminder.schedule(this);
                if (android.os.Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
                home();
            }, start / 60, start % 60, android.text.format.DateFormat.is24HourFormat(this)).show();
        }).show();
    }

    private String skillName(int skill) {
        switch (skill) {
            case Course.SPELLING: return getString(R.string.learn_skill_spelling);
            case Course.ARTICLE: return getString(R.string.learn_skill_article);
            case Course.FORM: return getString(R.string.learn_skill_form);
            case Course.SPEAKING: return getString(R.string.learn_skill_speaking);
            default: return getString(R.string.learn_skill_meaning);
        }
    }

    // ------------------------------------------------------------------ a lesson

    private void run(List<Course.Exercise> ex, String title) {
        lesson = new ArrayList<>(ex);
        at = 0;
        right = 0;
        answeredCount = 0;
        noSpeaking = false;
        movedOn.clear();
        newWords.clear();
        troubles.clear();
        page.title.setText(title);
        show();
    }

    private void show() {
        // "I can't speak now": the speaking questions of this lesson are left out
        while (at < lesson.size() && noSpeaking && lesson.get(at).kind == Course.SAY) at++;
        if (at >= lesson.size()) {
            finish(true);
            return;
        }
        answered = false;
        Course.Exercise e = lesson.get(at);
        LinearLayout c = page.content;
        c.removeAllViews();
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(lesson.size());
        bar.setProgress(at);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        bar.setContentDescription(getString(R.string.practice_count, at + 1, lesson.size()));
        c.addView(bar, Page.full(ui.dp(2), ui.dp(14)));

        Course.WordState w = e.word;
        LinearLayout card = box(ui.surface);
        TextView ask = ui.text(14, ui.sub, Ui.REGULAR);
        card.addView(ask);
        TextView prompt = ui.text(30, ui.text, Ui.MEDIUM);
        Ui.bidi(prompt);
        TextView hint = ui.text(15, ui.sub, Ui.REGULAR);
        hint.setLineSpacing(0, 1.25f);
        Ui.bidi(hint);
        field = null;
        switch (e.kind) {
            case Course.INTRO:
                ask.setText(R.string.learn_new_word);
                prompt.setText(w.display);
                hint.setText(introText(w));
                say(w.display, w.lang);
                break;
            case Course.CHOOSE_MEANING:
                ask.setText(R.string.learn_ask_meaning);
                prompt.setText(w.display);
                say(w.display, w.lang);
                break;
            case Course.CHOOSE_WORD:
                ask.setText(getString(R.string.learn_ask_word, w.lang.label));
                prompt.setText(Course.meaning(w));
                break;
            case Course.WRITE:
                ask.setText(getString(R.string.practice_write_task, w.lang.label));
                prompt.setText(Course.meaning(w));
                if (w.noun) hint.setText(R.string.learn_with_article);
                break;
            case Course.ARTICLE_Q:
                ask.setText(R.string.practice_article_task);
                prompt.setText(w.word);
                hint.setText(Course.meaning(w));
                break;
            case Course.FORM_Q:
                ask.setText(R.string.practice_verb_task);
                prompt.setText(e.person + " ___");
                hint.setText(w.word + " · " + e.tense);
                break;
            default:
                ask.setText(R.string.practice_say_task);
                prompt.setText(w.display);
                hint.setText(Course.meaning(w));
        }
        if (e.again) ask.setText(getString(R.string.learn_again_prefix, ask.getText()));
        if (prompt.getText().length() > 22) prompt.setTextSize(22);
        card.addView(prompt, Page.full(ui.dp(6), 0));
        if (hint.getText().length() > 0) card.addView(hint, Page.full(ui.dp(6), 0));
        if (e.kind == Course.INTRO || e.kind == Course.CHOOSE_MEANING || e.kind == Course.SAY || e.kind == Course.ARTICLE_Q) {
            LinearLayout listen = ui.actionPill(Icons.Kind.SPEAK, getString(R.string.listen));
            listen.setOnClickListener(v -> say(e.kind == Course.ARTICLE_Q ? w.word : w.display, w.lang));
            card.addView(listen, Page.full(ui.dp(12), 0));
        }
        c.addView(card, Page.full());

        switch (e.kind) {
            case Course.INTRO: {
                TextView ok = ui.pill(getString(R.string.learn_got_it), true);
                ok.setOnClickListener(v -> {
                    course.met(w, System.currentTimeMillis());
                    newWords.add(w);
                    // it joins the saved words (folder of the level), so Review and the widget bring it back
                    nb.save(w.lang, w.word, w.back, getString(R.string.learn_folder, w.lang.label, level()), System.currentTimeMillis());
                    at++;
                    show();
                });
                c.addView(ok, Page.full(ui.dp(16), 0));
                ok.getLayoutParams().height = ui.dp(50);
                return;
            }
            case Course.CHOOSE_MEANING:
            case Course.CHOOSE_WORD:
            case Course.ARTICLE_Q: {
                String rightOne = e.kind == Course.CHOOSE_MEANING ? Course.meaning(w) : e.kind == Course.CHOOSE_WORD ? w.display
                        : w.display.split(" ")[0];
                LinearLayout grid = new LinearLayout(this);
                grid.setOrientation(LinearLayout.VERTICAL);
                List<TextView> buttons = new ArrayList<>();
                for (String choice : e.choices) {
                    TextView b = ui.pill(choice, false);
                    b.setTextSize(17);
                    Ui.bidi(b);
                    b.setOnClickListener(v -> {
                        if (answered) return;
                        boolean ok = choice.equals(rightOne);
                        for (TextView x : buttons) {
                            if (x.getText().toString().equals(rightOne)) x.setBackground(ui.round(green(), 20));
                            else if (x == b) x.setBackground(ui.round(ui.danger, 20));
                            x.setTextColor(x.getText().toString().equals(rightOne) || x == b ? 0xFFFFFFFF : ui.sub);
                        }
                        answer(ok ? WordQuiz.RIGHT : WordQuiz.WRONG, choice, rightOne);
                    });
                    buttons.add(b);
                    grid.addView(b, Page.full(0, ui.dp(10)));
                    b.getLayoutParams().height = ui.dp(54);
                }
                c.addView(grid, Page.full(ui.dp(16), 0));
                break;
            }
            case Course.WRITE:
            case Course.FORM_Q: {
                field = new EditText(this);
                field.setHint(e.kind == Course.WRITE ? getString(R.string.practice_type_word, w.lang.label) : getString(R.string.practice_type_form));
                field.setHintTextColor(ui.sub);
                field.setTextColor(ui.text);
                field.setTextSize(19);
                field.setSingleLine(true);
                field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                field.setImeOptions(EditorInfo.IME_ACTION_DONE);
                field.setBackground(ui.outline(ui.bg, ui.line, 14));
                field.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
                field.setContentDescription(getString(R.string.practice_answer));
                field.setOnEditorActionListener((v, id, ev) -> {
                    checkTyped(e);
                    return true;
                });
                c.addView(field, Page.full(ui.dp(16), 0));
                TextView check = ui.pill(getString(R.string.check), true);
                check.setOnClickListener(v -> checkTyped(e));
                c.addView(check, Page.full(ui.dp(10), 0));
                check.getLayoutParams().height = ui.dp(50);
                field.requestFocus();
                field.postDelayed(() -> {
                    InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (im != null && field != null) im.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
                }, 250);
                break;
            }
            default: {
                LinearLayout mic = ui.actionPill(Icons.Kind.MIC, getString(R.string.practice_say_now));
                mic.setOnClickListener(v -> listen(w));
                c.addView(mic, Page.full(ui.dp(16), 0));
                TextView cant = ui.pill(getString(R.string.learn_cant_speak), false);
                cant.setOnClickListener(v -> {
                    noSpeaking = true;
                    at++;
                    show();
                });
                c.addView(cant, Page.full(ui.dp(10), 0));
                cant.getLayoutParams().height = ui.dp(46);
            }
        }
        feedback = ui.text(16, ui.text, Ui.MEDIUM);
        feedback.setLineSpacing(0, 1.25f);
        feedback.setVisibility(View.GONE);
        Ui.bidi(feedback);
        c.addView(feedback, Page.full(ui.dp(14), 0));
        go = ui.pill(getString(R.string.learn_continue), true);
        go.setVisibility(View.GONE);
        go.setOnClickListener(v -> {
            at++;
            show();
        });
        c.addView(go, Page.full(ui.dp(12), ui.dp(20)));
        go.getLayoutParams().height = ui.dp(50);
    }

    private int green() {
        return ui.dark ? 0xFF22A05A : 0xFF16A34A;
    }

    /** For a new word: its translations, the plural of a noun, the main forms of a verb. */
    private String introText(Course.WordState w) {
        StringBuilder b = new StringBuilder(w.back);
        if (w.noun && Lexicon.nouns() != null) {
            Nouns.Info n = Lexicon.nouns().exact(w.word);
            if (n != null && !n.plural().isEmpty()) b.append('\n').append(getString(R.string.learn_plural, n.plural()));
        }
        if (w.verb) {
            Conjugation c = Conjugation.of(w.lang, w.word.toLowerCase(Locale.ROOT));
            if (c != null) {
                List<String> forms = new ArrayList<>();
                for (Conjugation.Tense t : c.tenses)
                    if (t.name.equals("Present") || t.name.equals("Simple past") || t.name.equals("Perfect"))
                        forms.add(t.persons[0] + " " + t.form(0));
                if (!forms.isEmpty()) b.append('\n').append(String.join(" · ", forms));
            }
        }
        return b.toString();
    }

    private void checkTyped(Course.Exercise e) {
        if (answered || field == null) return;
        String typed = field.getText().toString();
        if (Text.clean(typed).isEmpty()) {
            field.requestFocus();
            return;
        }
        Course.WordState w = e.word;
        List<String> ok = new ArrayList<>();
        String rightOne;
        if (e.kind == Course.FORM_Q) {
            for (String f : e.form.split(" / ")) ok.add(f.trim());
            rightOne = e.form;
        } else {
            ok.add(w.display);
            rightOne = w.display;
        }
        WordQuiz.Question q = new WordQuiz.Question(WordQuiz.WRITE, w.lang, "", "", rightOne, ok, new ArrayList<>(), w.word);
        int v = WordQuiz.check(q, typed);
        // a noun without its article: the word is right, the article is part of learning it
        if (v == WordQuiz.WRONG && e.kind == Course.WRITE && w.noun) {
            WordQuiz.Question bare = new WordQuiz.Question(WordQuiz.WRITE, w.lang, "", "", w.word,
                    java.util.Collections.singletonList(w.word), new ArrayList<>(), w.word);
            if (WordQuiz.check(bare, typed) != WordQuiz.WRONG) v = WordQuiz.ALMOST;
        }
        field.setEnabled(false);
        answer(v, typed, rightOne);
    }

    /** Shows how it went, counts it (stage, mistakes, review, progress) and offers Continue. */
    private void answer(int verdict, String given, String rightOne) {
        if (answered) return;
        answered = true;
        Course.Exercise e = lesson.get(at);
        Course.WordState w = e.word;
        long now = System.currentTimeMillis();
        boolean ok = verdict != WordQuiz.WRONG;
        answeredCount++;
        if (ok) right++;
        course.answer(e, verdict, movedOn.add(w.key()), now);
        nb.practiced(ok, now);
        if (e.kind == Course.SAY && ok) nb.spoke(now);
        Notebook.Card card = nb.get(w.lang, w.word);
        if (card != null && card.due <= now && !e.again) nb.answer(card, ok ? Notebook.GOOD : Notebook.AGAIN, now);
        if (!ok) {
            if (!troubles.contains(w)) troubles.add(w);
            if (!e.again) lesson.add(e.repeat());                 // asked again at the end
            bar.setMax(lesson.size());
        }
        int orange = ui.dark ? 0xFFFBBF24 : 0xFFD97706;
        if (verdict == WordQuiz.RIGHT) {
            feedback.setText(getString(R.string.learn_right, pick(R.array.learn_praise)));
            feedback.setTextColor(green());
            Feel.right(feedback);
        } else if (verdict == WordQuiz.ALMOST) {
            feedback.setText(e.kind == Course.SAY ? getString(R.string.practice_say_almost, given, rightOne)
                    : getString(R.string.practice_almost, rightOne));
            feedback.setTextColor(orange);
            Feel.right(feedback);
        } else {
            feedback.setText(e.kind == Course.SAY ? getString(R.string.practice_say_wrong, given, rightOne)
                    : getString(R.string.learn_wrong, rightOne, Course.meaning(w)));
            feedback.setTextColor(ui.danger);
            Feel.wrong(feedback);
        }
        feedback.setVisibility(View.VISIBLE);
        go.setVisibility(View.VISIBLE);
        if (e.kind != Course.SAY && e.kind != Course.CHOOSE_MEANING) say(e.kind == Course.FORM_Q ? e.person + " " + e.form : w.display, w.lang);
        course.save();
        page.content.post(() -> page.scroll.smoothScrollTo(0, Math.max(0, go.getBottom() - page.scroll.getHeight() + ui.dp(24))));
    }

    private String pick(int array) {
        String[] a = getResources().getStringArray(array);
        return a[random.nextInt(a.length)];
    }

    /** The end of a lesson: how it went, the words to practise again, what comes tomorrow. */
    private void finish(boolean completed) {
        long now = System.currentTimeMillis();
        int percent = answeredCount == 0 ? 100 : Math.round(100f * right / answeredCount);
        boolean easy = completed && answeredCount > 0 && course.finished(lang(), percent, now);
        course.save();
        lesson = null;
        page.title.setText(R.string.learn);
        LinearLayout c = page.content;
        c.removeAllViews();
        TextView done = ui.text(28, ui.text, Ui.MEDIUM);
        done.setText(R.string.learn_lesson_done);
        c.addView(done, Page.full(ui.dp(16), ui.dp(4)));
        TextView score = ui.text(17, percent >= 80 ? green() : ui.text, Ui.MEDIUM);
        score.setText(getString(R.string.learn_score, right, answeredCount, percent));
        c.addView(score, Page.full(0, ui.dp(16)));
        if (!newWords.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Course.WordState w : newWords) b.append(b.length() > 0 ? ", " : "").append(w.display);
            c.addView(summaryBox(getString(R.string.learn_new_words, newWords.size()), b.toString()), Page.full(0, ui.dp(10)));
        }
        if (!troubles.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Course.WordState w : troubles) {
                if (b.length() > 0) b.append('\n');
                b.append(w.display).append(" · ").append(skillName(w.weakest() < 0 ? Course.MEANING : w.weakest()));
            }
            c.addView(summaryBox(getString(R.string.learn_practise_again), b.toString()), Page.full(0, ui.dp(10)));
        }
        int tomorrow = nb.due(null, null, now + Progress.DAY, 500).size();
        Course.Track t = course.track(lang());
        c.addView(summaryBox(getString(R.string.learn_tomorrow), getString(R.string.learn_tomorrow_plan, tomorrow, t.dailyNew)),
                Page.full(0, ui.dp(10)));
        int streak = nb.progress.streak(now);
        TextView st = ui.text(15, ui.accent, Ui.MEDIUM);
        st.setText(getResources().getQuantityString(R.plurals.streak_days, streak, streak));
        c.addView(st, Page.full(ui.dp(4), ui.dp(16)));
        if (easy) {
            int i = java.util.Arrays.asList("A1", "A2", "B1", "B2", "C1").indexOf(t.level);
            if (i >= 0 && i < 4) {
                String next = new String[]{"A1", "A2", "B1", "B2", "C1"}[i + 1];
                TextView up = ui.pill(getString(R.string.learn_move_up, next), false);
                up.setOnClickListener(v -> {
                    t.level = next;
                    store.setMyLevel(lang(), next);
                    course.save();
                    home();
                });
                c.addView(up, Page.full(0, ui.dp(10)));
                up.getLayoutParams().height = ui.dp(48);
            }
        }
        TextView back = ui.pill(getString(R.string.learn_done), true);
        back.setOnClickListener(v -> home());
        c.addView(back, Page.full(0, ui.dp(20)));
        back.getLayoutParams().height = ui.dp(50);
        WidgetProvider.updateAll(this);
    }

    private View summaryBox(String title, String text) {
        LinearLayout b = box(ui.surface);
        TextView h = ui.line(13, ui.accent, Ui.MEDIUM);
        h.setText(title);
        b.addView(h);
        TextView t = ui.text(16, ui.text, Ui.REGULAR);
        t.setText(text);
        t.setLineSpacing(0, 1.3f);
        Ui.bidi(t);
        b.addView(t, Page.full(ui.dp(4), 0));
        return b;
    }

    // ------------------------------------------------------------------ speech

    private void say(String text, Lang l) {
        if (tts == null || !ttsReady) return;
        try {
            tts.setLanguage(MainActivity.locale(l));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "learn");
        } catch (RuntimeException ignored) {
            // no voice for this language
        }
    }

    @SuppressWarnings("deprecation")
    private void listen(Course.WordState w) {
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        if (it.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        String tag = w.lang == Lang.DE ? "de-DE" : "en-US";
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        it.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.practice_say_prompt, w.display));
        try {
            startActivityForResult(it, SPEECH);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SPEECH || lesson == null || at >= lesson.size() || answered) return;
        if (result != RESULT_OK || data == null) return;
        List<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        Course.WordState w = lesson.get(at).word;
        List<String> ok = new ArrayList<>();
        ok.add(w.word);
        ok.add(w.display);
        WordQuiz.Question q = new WordQuiz.Question(WordQuiz.SAY, w.lang, "", "", w.display, ok, new ArrayList<>(), w.word);
        int v = WordQuiz.heard(q, heard);
        if (v == WordQuiz.WRONG) {
            feedback.setText(getString(R.string.practice_say_try, heard.get(0)));
            feedback.setTextColor(ui.danger);
            feedback.setVisibility(View.VISIBLE);
            Feel.wrong(feedback);
            return;
        }
        answer(v, heard.get(0), w.display);
    }
}
