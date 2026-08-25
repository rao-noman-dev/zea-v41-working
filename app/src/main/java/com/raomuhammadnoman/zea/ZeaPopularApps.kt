package com.raomuhammadnoman.zea

/**
 * Additional curated package-backed applications that Zea may recognize and launch.
 *
 * Core package apps and system actions remain in ZeaRegistry.kt. This file
 * contains only additional apps so the final registry has one definition per app.
 * Banking, payment, wallet, trading, and cryptocurrency apps are excluded.
 */
val zeaPopularApps: List<AppRegistryEntry> = listOf(
    popularApp(
        key = "whatsapp_business",
        displayName = "WhatsApp Business",
        category = AppCategory.COMMUNICATION,
        packageName = "com.whatsapp.w4b",
        aliases = listOf("whatsapp business", "whats app business", "wa business")
    ),
    popularApp(
        key = "telegram",
        displayName = "Telegram",
        category = AppCategory.COMMUNICATION,
        packageName = "org.telegram.messenger",
        aliases = listOf("telegram messenger", "tele gram")
    ),
    popularApp(
        key = "signal",
        displayName = "Signal",
        category = AppCategory.COMMUNICATION,
        packageName = "org.thoughtcrime.securesms",
        aliases = listOf("signal messenger", "signal private messenger")
    ),
    popularApp(
        key = "discord",
        displayName = "Discord",
        category = AppCategory.COMMUNICATION,
        packageName = "com.discord",
        aliases = listOf("discord app")
    ),
    popularApp(
        key = "messenger",
        displayName = "Messenger",
        category = AppCategory.COMMUNICATION,
        packageName = "com.facebook.orca",
        aliases = listOf("facebook messenger", "meta messenger")
    ),
    popularApp(
        key = "viber",
        displayName = "Viber",
        category = AppCategory.COMMUNICATION,
        packageName = "com.viber.voip",
        aliases = listOf("rakuten viber", "viber messenger")
    ),
    popularApp(
        key = "imo",
        displayName = "imo",
        category = AppCategory.COMMUNICATION,
        packageName = "com.imo.android.imoim",
        aliases = listOf("imo messenger", "imo calls")
    ),
    popularApp(
        key = "skype",
        displayName = "Skype",
        category = AppCategory.COMMUNICATION,
        packageName = "com.skype.raider",
        aliases = listOf("skype app")
    ),
    popularApp(
        key = "zoom",
        displayName = "Zoom",
        category = AppCategory.COMMUNICATION,
        packageName = "us.zoom.videomeetings",
        aliases = listOf("zoom workplace", "zoom meetings")
    ),
    popularApp(
        key = "google_meet",
        displayName = "Google Meet",
        category = AppCategory.COMMUNICATION,
        packageName = "com.google.android.apps.tachyon",
        aliases = listOf("meet", "google video meet")
    ),
    popularApp(
        key = "microsoft_teams",
        displayName = "Microsoft Teams",
        category = AppCategory.COMMUNICATION,
        packageName = "com.microsoft.teams",
        aliases = listOf("teams", "ms teams")
    ),
    popularApp(
        key = "slack",
        displayName = "Slack",
        category = AppCategory.COMMUNICATION,
        packageName = "com.Slack",
        aliases = listOf("slack app")
    ),
    popularApp(
        key = "line",
        displayName = "LINE",
        category = AppCategory.COMMUNICATION,
        packageName = "jp.naver.line.android",
        aliases = listOf("line messenger", "line calls")
    ),
    popularApp(
        key = "wechat",
        displayName = "WeChat",
        category = AppCategory.COMMUNICATION,
        packageName = "com.tencent.mm",
        aliases = listOf("we chat", "wechat messenger")
    ),
    popularApp(
        key = "facebook",
        displayName = "Facebook",
        category = AppCategory.SOCIAL,
        packageName = "com.facebook.katana",
        aliases = listOf("facebook app", "fb")
    ),
    popularApp(
        key = "x",
        displayName = "X",
        category = AppCategory.SOCIAL,
        packageName = "com.twitter.android",
        aliases = listOf("twitter", "x twitter", "x app")
    ),
    popularApp(
        key = "threads",
        displayName = "Threads",
        category = AppCategory.SOCIAL,
        packageName = "com.instagram.barcelona",
        aliases = listOf("instagram threads", "threads app")
    ),
    popularApp(
        key = "reddit",
        displayName = "Reddit",
        category = AppCategory.SOCIAL,
        packageName = "com.reddit.frontpage",
        aliases = listOf("reddit app")
    ),
    popularApp(
        key = "pinterest",
        displayName = "Pinterest",
        category = AppCategory.SOCIAL,
        packageName = "com.pinterest",
        aliases = listOf("pin interest")
    ),
    popularApp(
        key = "snapchat",
        displayName = "Snapchat",
        category = AppCategory.SOCIAL,
        packageName = "com.snapchat.android",
        aliases = listOf("snap chat", "snap")
    ),
    popularApp(
        key = "quora",
        displayName = "Quora",
        category = AppCategory.SOCIAL,
        packageName = "com.quora.android",
        aliases = listOf("quora app")
    ),
    popularApp(
        key = "tumblr",
        displayName = "Tumblr",
        category = AppCategory.SOCIAL,
        packageName = "com.tumblr",
        aliases = listOf("tumblr app")
    ),
    popularApp(
        key = "bereal",
        displayName = "BeReal",
        category = AppCategory.SOCIAL,
        packageName = "com.bereal.ft",
        aliases = listOf("be real", "bereal app")
    ),
    popularApp(
        key = "bluesky",
        displayName = "Bluesky",
        category = AppCategory.SOCIAL,
        packageName = "xyz.blueskyweb.app",
        aliases = listOf("blue sky", "bluesky social")
    ),
    popularApp(
        key = "mastodon",
        displayName = "Mastodon",
        category = AppCategory.SOCIAL,
        packageName = "org.joinmastodon.android",
        aliases = listOf("mastodon social")
    ),
    popularApp(
        key = "lemon8",
        displayName = "Lemon8",
        category = AppCategory.SOCIAL,
        packageName = "com.bd.nproject",
        aliases = listOf("lemon 8", "lemon eight")
    ),
    popularApp(
        key = "outlook",
        displayName = "Microsoft Outlook",
        category = AppCategory.PRODUCTIVITY,
        packageName = "com.microsoft.office.outlook",
        aliases = listOf("outlook", "outlook mail")
    ),
    popularApp(
        key = "onedrive",
        displayName = "Microsoft OneDrive",
        category = AppCategory.CLOUD_STORAGE,
        packageName = "com.microsoft.skydrive",
        aliases = listOf("one drive", "onedrive")
    ),
    popularApp(
        key = "dropbox",
        displayName = "Dropbox",
        category = AppCategory.CLOUD_STORAGE,
        packageName = "com.dropbox.android",
        aliases = listOf("drop box")
    ),
    popularApp(
        key = "google_docs",
        displayName = "Google Docs",
        category = AppCategory.DOCUMENTS,
        packageName = "com.google.android.apps.docs.editors.docs",
        aliases = listOf("docs", "google documents")
    ),
    popularApp(
        key = "google_sheets",
        displayName = "Google Sheets",
        category = AppCategory.DOCUMENTS,
        packageName = "com.google.android.apps.docs.editors.sheets",
        aliases = listOf("sheets", "google spreadsheet")
    ),
    popularApp(
        key = "google_slides",
        displayName = "Google Slides",
        category = AppCategory.DOCUMENTS,
        packageName = "com.google.android.apps.docs.editors.slides",
        aliases = listOf("slides", "google presentation")
    ),
    popularApp(
        key = "microsoft_word",
        displayName = "Microsoft Word",
        category = AppCategory.DOCUMENTS,
        packageName = "com.microsoft.office.word",
        aliases = listOf("word", "ms word")
    ),
    popularApp(
        key = "microsoft_excel",
        displayName = "Microsoft Excel",
        category = AppCategory.DOCUMENTS,
        packageName = "com.microsoft.office.excel",
        aliases = listOf("excel", "ms excel")
    ),
    popularApp(
        key = "microsoft_powerpoint",
        displayName = "Microsoft PowerPoint",
        category = AppCategory.DOCUMENTS,
        packageName = "com.microsoft.office.powerpoint",
        aliases = listOf("powerpoint", "power point", "ms powerpoint")
    ),
    popularApp(
        key = "microsoft_onenote",
        displayName = "Microsoft OneNote",
        category = AppCategory.PRODUCTIVITY,
        packageName = "com.microsoft.office.onenote",
        aliases = listOf("onenote", "one note")
    ),
    popularApp(
        key = "notion",
        displayName = "Notion",
        category = AppCategory.PRODUCTIVITY,
        packageName = "notion.id",
        aliases = listOf("notion app")
    ),
    popularApp(
        key = "evernote",
        displayName = "Evernote",
        category = AppCategory.PRODUCTIVITY,
        packageName = "com.evernote",
        aliases = listOf("ever note")
    ),
    popularApp(
        key = "todoist",
        displayName = "Todoist",
        category = AppCategory.PRODUCTIVITY,
        packageName = "com.todoist",
        aliases = listOf("to do ist", "todoist tasks")
    ),
    popularApp(
        key = "trello",
        displayName = "Trello",
        category = AppCategory.PRODUCTIVITY,
        packageName = "com.trello",
        aliases = listOf("trello board")
    ),
    popularApp(
        key = "asana",
        displayName = "Asana",
        category = AppCategory.PRODUCTIVITY,
        packageName = "com.asana.app",
        aliases = listOf("asana work")
    ),
    popularApp(
        key = "clickup",
        displayName = "ClickUp",
        category = AppCategory.PRODUCTIVITY,
        packageName = "co.mangotechnologies.clickup",
        aliases = listOf("click up", "clickup tasks")
    ),
    popularApp(
        key = "google_keep",
        displayName = "Google Keep",
        category = AppCategory.PRODUCTIVITY,
        packageName = "com.google.android.keep",
        aliases = listOf("keep", "keep notes")
    ),
    popularApp(
        key = "adobe_acrobat",
        displayName = "Adobe Acrobat",
        category = AppCategory.DOCUMENTS,
        packageName = "com.adobe.reader",
        aliases = listOf("acrobat", "adobe reader", "pdf reader")
    ),
    popularApp(
        key = "microsoft_lens",
        displayName = "Microsoft Lens",
        category = AppCategory.DOCUMENTS,
        packageName = "com.microsoft.office.officelens",
        aliases = listOf("office lens", "ms lens")
    ),
    popularApp(
        key = "wps_office",
        displayName = "WPS Office",
        category = AppCategory.DOCUMENTS,
        packageName = "cn.wps.moffice_eng",
        aliases = listOf("wps", "wps documents")
    ),
    popularApp(
        key = "canva",
        displayName = "Canva",
        category = AppCategory.PRODUCTIVITY,
        packageName = "com.canva.editor",
        aliases = listOf("canva editor")
    ),
    popularApp(
        key = "adobe_scan",
        displayName = "Adobe Scan",
        category = AppCategory.DOCUMENTS,
        packageName = "com.adobe.scan.android",
        aliases = listOf("adobe scanner", "scan pdf")
    ),
    popularApp(
        key = "coursera",
        displayName = "Coursera",
        category = AppCategory.EDUCATION,
        packageName = "org.coursera.android",
        aliases = listOf("coursera learning")
    ),
    popularApp(
        key = "khan_academy",
        displayName = "Khan Academy",
        category = AppCategory.EDUCATION,
        packageName = "org.khanacademy.android",
        aliases = listOf("khan", "khanacademy")
    ),
    popularApp(
        key = "duolingo",
        displayName = "Duolingo",
        category = AppCategory.EDUCATION,
        packageName = "com.duolingo",
        aliases = listOf("duo lingo")
    ),
    popularApp(
        key = "udemy",
        displayName = "Udemy",
        category = AppCategory.EDUCATION,
        packageName = "com.udemy.android",
        aliases = listOf("udemy courses")
    ),
    popularApp(
        key = "edx",
        displayName = "edX",
        category = AppCategory.EDUCATION,
        packageName = "org.edx.mobile",
        aliases = listOf("ed x", "edx learning")
    ),
    popularApp(
        key = "quizlet",
        displayName = "Quizlet",
        category = AppCategory.EDUCATION,
        packageName = "com.quizlet.quizletandroid",
        aliases = listOf("quiz let")
    ),
    popularApp(
        key = "photomath",
        displayName = "Photomath",
        category = AppCategory.EDUCATION,
        packageName = "com.microblink.photomath",
        aliases = listOf("photo math")
    ),
    popularApp(
        key = "google_classroom",
        displayName = "Google Classroom",
        category = AppCategory.EDUCATION,
        packageName = "com.google.android.apps.classroom",
        aliases = listOf("classroom", "google class room")
    ),
    popularApp(
        key = "moodle",
        displayName = "Moodle",
        category = AppCategory.EDUCATION,
        packageName = "com.moodle.moodlemobile",
        aliases = listOf("moodle learning")
    ),
    popularApp(
        key = "brilliant",
        displayName = "Brilliant",
        category = AppCategory.EDUCATION,
        packageName = "org.brilliant.android",
        aliases = listOf("brilliant learning")
    ),
    popularApp(
        key = "sololearn",
        displayName = "Sololearn",
        category = AppCategory.EDUCATION,
        packageName = "com.sololearn",
        aliases = listOf("solo learn")
    ),
    popularApp(
        key = "mimo",
        displayName = "Mimo",
        category = AppCategory.EDUCATION,
        packageName = "com.getmimo",
        aliases = listOf("mimo coding")
    ),
    popularApp(
        key = "ted",
        displayName = "TED",
        category = AppCategory.EDUCATION,
        packageName = "com.ted.android",
        aliases = listOf("ted talks", "ted talk")
    ),
    popularApp(
        key = "memrise",
        displayName = "Memrise",
        category = AppCategory.EDUCATION,
        packageName = "com.memrise.android.memrisecompanion",
        aliases = listOf("mem rise")
    ),
    popularApp(
        key = "busuu",
        displayName = "Busuu",
        category = AppCategory.EDUCATION,
        packageName = "com.busuu.android.enc",
        aliases = listOf("busu", "busuu languages")
    ),
    popularApp(
        key = "waze",
        displayName = "Waze",
        category = AppCategory.TRAVEL,
        packageName = "com.waze",
        aliases = listOf("waze navigation")
    ),
    popularApp(
        key = "uber",
        displayName = "Uber",
        category = AppCategory.TRANSPORT,
        packageName = "com.ubercab",
        aliases = listOf("uber ride", "uber taxi")
    ),
    popularApp(
        key = "careem",
        displayName = "Careem",
        category = AppCategory.TRANSPORT,
        packageName = "com.careem.acma",
        aliases = listOf("careem ride", "careem taxi")
    ),
    popularApp(
        key = "indrive",
        displayName = "inDrive",
        category = AppCategory.TRANSPORT,
        packageName = "sinet.startup.inDriver",
        aliases = listOf("in drive", "indriver")
    ),
    popularApp(
        key = "bolt",
        displayName = "Bolt",
        category = AppCategory.TRANSPORT,
        packageName = "ee.mtakso.client",
        aliases = listOf("bolt ride", "bolt taxi")
    ),
    popularApp(
        key = "booking",
        displayName = "Booking.com",
        category = AppCategory.TRAVEL,
        packageName = "com.booking",
        aliases = listOf("booking", "booking com")
    ),
    popularApp(
        key = "airbnb",
        displayName = "Airbnb",
        category = AppCategory.TRAVEL,
        packageName = "com.airbnb.android",
        aliases = listOf("air bnb")
    ),
    popularApp(
        key = "agoda",
        displayName = "Agoda",
        category = AppCategory.TRAVEL,
        packageName = "com.agoda.mobile.consumer",
        aliases = listOf("agoda hotels")
    ),
    popularApp(
        key = "trip_com",
        displayName = "Trip.com",
        category = AppCategory.TRAVEL,
        packageName = "ctrip.english",
        aliases = listOf("trip com", "trip dot com")
    ),
    popularApp(
        key = "skyscanner",
        displayName = "Skyscanner",
        category = AppCategory.TRAVEL,
        packageName = "net.skyscanner.android.main",
        aliases = listOf("sky scanner")
    ),
    popularApp(
        key = "tripadvisor",
        displayName = "Tripadvisor",
        category = AppCategory.TRAVEL,
        packageName = "com.tripadvisor.tripadvisor",
        aliases = listOf("trip advisor")
    ),
    popularApp(
        key = "google_earth",
        displayName = "Google Earth",
        category = AppCategory.TRAVEL,
        packageName = "com.google.earth",
        aliases = listOf("earth", "google globe")
    ),
    popularApp(
        key = "moovit",
        displayName = "Moovit",
        category = AppCategory.TRANSPORT,
        packageName = "com.tranzmate",
        aliases = listOf("moo vit", "transit tracker")
    ),
    popularApp(
        key = "rome2rio",
        displayName = "Rome2Rio",
        category = AppCategory.TRAVEL,
        packageName = "com.rome2rio.www.rome2rio",
        aliases = listOf("rome 2 rio", "rome to rio")
    ),
    popularApp(
        key = "amazon_shopping",
        displayName = "Amazon Shopping",
        category = AppCategory.SHOPPING,
        packageName = "com.amazon.mShop.android.shopping",
        aliases = listOf("amazon", "amazon shop")
    ),
    popularApp(
        key = "aliexpress",
        displayName = "AliExpress",
        category = AppCategory.SHOPPING,
        packageName = "com.alibaba.aliexpresshd",
        aliases = listOf("ali express")
    ),
    popularApp(
        key = "ebay",
        displayName = "eBay",
        category = AppCategory.SHOPPING,
        packageName = "com.ebay.mobile",
        aliases = listOf("e bay")
    ),
    popularApp(
        key = "daraz",
        displayName = "Daraz",
        category = AppCategory.SHOPPING,
        packageName = "com.daraz.android",
        aliases = listOf("daraz shopping")
    ),
    popularApp(
        key = "temu",
        displayName = "Temu",
        category = AppCategory.SHOPPING,
        packageName = "com.einnovation.temu",
        aliases = listOf("temu shopping")
    ),
    popularApp(
        key = "shein",
        displayName = "SHEIN",
        category = AppCategory.SHOPPING,
        packageName = "com.zzkko",
        aliases = listOf("she in", "shein shopping")
    ),
    popularApp(
        key = "olx",
        displayName = "OLX Pakistan",
        category = AppCategory.SHOPPING,
        packageName = "com.olx.pk",
        aliases = listOf("olx", "olx pk")
    ),
    popularApp(
        key = "foodpanda",
        displayName = "foodpanda",
        category = AppCategory.FOOD,
        packageName = "com.global.foodpanda.android",
        aliases = listOf("food panda")
    ),
    popularApp(
        key = "uber_eats",
        displayName = "Uber Eats",
        category = AppCategory.FOOD,
        packageName = "com.ubercab.eats",
        aliases = listOf("ubereats", "uber food")
    ),
    popularApp(
        key = "talabat",
        displayName = "talabat",
        category = AppCategory.FOOD,
        packageName = "com.talabat",
        aliases = listOf("talabat food")
    ),
    popularApp(
        key = "deliveroo",
        displayName = "Deliveroo",
        category = AppCategory.FOOD,
        packageName = "com.deliveroo.orderapp",
        aliases = listOf("deliveroo food")
    ),
    popularApp(
        key = "instacart",
        displayName = "Instacart",
        category = AppCategory.FOOD,
        packageName = "com.instacart.client",
        aliases = listOf("insta cart", "grocery delivery")
    ),
    popularApp(
        key = "google_fit",
        displayName = "Google Fit",
        category = AppCategory.FITNESS,
        packageName = "com.google.android.apps.fitness",
        aliases = listOf("fit", "google fitness")
    ),
    popularApp(
        key = "samsung_health",
        displayName = "Samsung Health",
        category = AppCategory.HEALTH,
        packageName = "com.sec.android.app.shealth",
        aliases = listOf("s health", "samsung fitness")
    ),
    popularApp(
        key = "fitbit",
        displayName = "Fitbit",
        category = AppCategory.FITNESS,
        packageName = "com.fitbit.FitbitMobile",
        aliases = listOf("fit bit")
    ),
    popularApp(
        key = "strava",
        displayName = "Strava",
        category = AppCategory.FITNESS,
        packageName = "com.strava",
        aliases = listOf("strava running")
    ),
    popularApp(
        key = "myfitnesspal",
        displayName = "MyFitnessPal",
        category = AppCategory.FITNESS,
        packageName = "com.myfitnesspal.android",
        aliases = listOf("my fitness pal", "fitness pal")
    ),
    popularApp(
        key = "nike_run_club",
        displayName = "Nike Run Club",
        category = AppCategory.FITNESS,
        packageName = "com.nike.plusgps",
        aliases = listOf("nike running", "nrc")
    ),
    popularApp(
        key = "adidas_running",
        displayName = "adidas Running",
        category = AppCategory.FITNESS,
        packageName = "com.runtastic.android",
        aliases = listOf("adidas run", "runtastic")
    ),
    popularApp(
        key = "health_connect",
        displayName = "Health Connect",
        category = AppCategory.HEALTH,
        packageName = "com.google.android.apps.healthdata",
        aliases = listOf("google health connect")
    ),
    popularApp(
        key = "headspace",
        displayName = "Headspace",
        category = AppCategory.HEALTH,
        packageName = "com.getsomeheadspace.android",
        aliases = listOf("head space", "headspace meditation")
    ),
    popularApp(
        key = "calm",
        displayName = "Calm",
        category = AppCategory.HEALTH,
        packageName = "com.calm.android",
        aliases = listOf("calm meditation")
    ),
    popularApp(
        key = "sleep_cycle",
        displayName = "Sleep Cycle",
        category = AppCategory.HEALTH,
        packageName = "com.northcube.sleepcycle",
        aliases = listOf("sleepcycle", "sleep tracker")
    ),
    popularApp(
        key = "flo",
        displayName = "Flo",
        category = AppCategory.HEALTH,
        packageName = "org.iggymedia.periodtracker",
        aliases = listOf("flo period tracker")
    ),
    popularApp(
        key = "youtube",
        displayName = "YouTube",
        category = AppCategory.MEDIA,
        packageName = "com.google.android.youtube",
        aliases = listOf("you tube", "yt")
    ),
    popularApp(
        key = "youtube_music",
        displayName = "YouTube Music",
        category = AppCategory.MEDIA,
        packageName = "com.google.android.apps.youtube.music",
        aliases = listOf("yt music", "youtube songs")
    ),
    popularApp(
        key = "netflix",
        displayName = "Netflix",
        category = AppCategory.ENTERTAINMENT,
        packageName = "com.netflix.mediaclient",
        aliases = listOf("net flix")
    ),
    popularApp(
        key = "prime_video",
        displayName = "Prime Video",
        category = AppCategory.ENTERTAINMENT,
        packageName = "com.amazon.avod.thirdpartyclient",
        aliases = listOf("amazon prime video", "primevideo")
    ),
    popularApp(
        key = "disney_plus",
        displayName = "Disney+",
        category = AppCategory.ENTERTAINMENT,
        packageName = "com.disney.disneyplus",
        aliases = listOf("disney plus")
    ),
    popularApp(
        key = "vlc",
        displayName = "VLC",
        category = AppCategory.MEDIA,
        packageName = "org.videolan.vlc",
        aliases = listOf("vlc player", "video lan")
    ),
    popularApp(
        key = "shazam",
        displayName = "Shazam",
        category = AppCategory.MEDIA,
        packageName = "com.shazam.android",
        aliases = listOf("shazam music")
    ),
    popularApp(
        key = "soundcloud",
        displayName = "SoundCloud",
        category = AppCategory.MEDIA,
        packageName = "com.soundcloud.android",
        aliases = listOf("sound cloud")
    ),
    popularApp(
        key = "twitch",
        displayName = "Twitch",
        category = AppCategory.ENTERTAINMENT,
        packageName = "tv.twitch.android.app",
        aliases = listOf("twitch live")
    ),
    popularApp(
        key = "firefox",
        displayName = "Firefox",
        category = AppCategory.UTILITIES,
        packageName = "org.mozilla.firefox",
        aliases = listOf("mozilla firefox", "firefox browser")
    ),
    popularApp(
        key = "microsoft_edge",
        displayName = "Microsoft Edge",
        category = AppCategory.UTILITIES,
        packageName = "com.microsoft.emmx",
        aliases = listOf("edge", "edge browser")
    ),
    popularApp(
        key = "brave",
        displayName = "Brave",
        category = AppCategory.UTILITIES,
        packageName = "com.brave.browser",
        aliases = listOf("brave browser")
    ),
    popularApp(
        key = "opera",
        displayName = "Opera",
        category = AppCategory.UTILITIES,
        packageName = "com.opera.browser",
        aliases = listOf("opera browser")
    ),
    popularApp(
        key = "google_translate",
        displayName = "Google Translate",
        category = AppCategory.UTILITIES,
        packageName = "com.google.android.apps.translate",
        aliases = listOf("translate", "google translator")
    )
)

private fun popularApp(
    key: String,
    displayName: String,
    category: AppCategory,
    packageName: String,
    aliases: List<String>
): AppRegistryEntry {
    return AppRegistryEntry(
        key = key,
        displayName = displayName,
        aliases = aliases,
        packageName = packageName,
        category = category
    )
}
